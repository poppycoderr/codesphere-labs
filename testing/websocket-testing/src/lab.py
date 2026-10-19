"""WebSocket 接口测试：一发一收的假设、服务端推送、乱序响应与关联 ID、超时、握手鉴权、关闭码、消息大小、异常断线与连接清理。

同一个进程里用 websockets 起本地服务，客户端连接 127.0.0.1，输出 TSV（键、说明）。
"""
import asyncio
import inspect
import json
import time

import websockets
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConcurrencyError, ConnectionClosedError, ConnectionClosedOK, InvalidStatus

TOKEN = "example_token"
open_connections = set()


def out(key, text):
    print(f"{key}\t{text}", flush=True)


async def handler(ws):
    open_connections.add(ws)
    try:
        if ws.request.path == "/push":
            # 连接建立后立刻推送一条通知，客户端的请求响应排在它后面
            await ws.send(json.dumps({"type": "notice", "text": "系统维护"}))
        async for raw in ws:
            msg = json.loads(raw)
            op = msg.get("op")
            if op == "echo":
                await ws.send(json.dumps({"id": msg["id"], "result": msg["text"]}))
            elif op == "slow_then_fast":
                # 先到的请求慢、后到的请求快：响应顺序与请求顺序相反
                asyncio.create_task(reply_later(ws, msg["id"], msg["delay"]))
            elif op == "silent":
                pass
            elif op == "kick":
                await ws.close(1008, "token expired")
            elif op == "crash":
                ws.transport.abort()
    finally:
        open_connections.discard(ws)


async def reply_later(ws, mid, delay):
    await asyncio.sleep(delay)
    await ws.send(json.dumps({"id": mid, "result": f"done-{mid}"}))


def process_request(conn, request):
    if request.path == "/secure" and request.headers.get("Authorization") != f"Bearer {TOKEN}":
        return conn.respond(401, "missing or invalid token\n")
    return None


class Client:
    """按 id 把响应分派给等待它的请求；没有 id 的推送消息进入单独的队列。"""

    def __init__(self, ws):
        self.ws = ws
        self.pending = {}
        self.pushes = asyncio.Queue()
        self.next_id = 0
        self.reader = asyncio.create_task(self._read())

    async def _read(self):
        async for raw in self.ws:
            msg = json.loads(raw)
            if "id" in msg and msg["id"] in self.pending:
                self.pending.pop(msg["id"]).set_result(msg)
            else:
                await self.pushes.put(msg)

    async def call(self, timeout=2, **payload):
        self.next_id += 1
        mid = self.next_id
        fut = asyncio.get_running_loop().create_future()
        self.pending[mid] = fut
        await self.ws.send(json.dumps({"id": mid, **payload}))
        async with asyncio.timeout(timeout):
            return await fut


async def main():
    out("env", f"websockets={websockets.__version__}")
    sig = inspect.signature(connect.__init__).parameters
    names = ["open_timeout", "ping_interval", "ping_timeout", "close_timeout", "max_size"]
    out("defaults", "客户端默认值：" + "，".join(f"{n}={sig[n].default}" for n in names) + "；recv() 本身没有超时参数")
    async with serve(handler, "127.0.0.1", 8765, process_request=process_request, max_size=2**20) as server:
        base = "ws://127.0.0.1:8765"

        async with connect(base + "/plain") as ws:
            await ws.send(json.dumps({"op": "echo", "id": 1, "text": "hi"}))
            out("naive.echo", f"echo 服务一发一收：收到 {await ws.recv()}")

        async with connect(base + "/push") as ws:
            await ws.send(json.dumps({"op": "echo", "id": 1, "text": "hi"}))
            got = json.loads(await ws.recv())
            out("naive.push", f"服务端先推送了一条通知：一发一收拿到的是 {got}，断言 result=='hi' 失败")

        async with connect(base + "/plain") as ws:
            await ws.send(json.dumps({"op": "slow_then_fast", "id": 1, "delay": 0.3}))
            await ws.send(json.dumps({"op": "slow_then_fast", "id": 2, "delay": 0.05}))
            first = json.loads(await ws.recv())
            out("naive.order", f"先发 id=1（慢）再发 id=2（快）：第一条收到的是 id={first['id']}；按发送顺序配对会把 2 的结果当成 1 的")
            await ws.recv()

        async with connect(base + "/push") as ws:
            c = Client(ws)
            r1, r2 = await asyncio.gather(c.call(op="slow_then_fast", delay=0.3), c.call(op="slow_then_fast", delay=0.05))
            push = await c.pushes.get()
            out("matched", f"按 id 分派：请求 1 得到 {r1['result']}，请求 2 得到 {r2['result']}；推送单独收到 {push['type']}:{push['text']}")
            c.reader.cancel()

        async with connect(base + "/plain") as ws:
            async def reader():
                return await ws.recv()
            t = asyncio.create_task(reader())
            await asyncio.sleep(0.05)
            try:
                await ws.recv()
                out("concurrent.recv", "两个协程同时 recv：未报错")
            except ConcurrencyError as e:
                out("concurrent.recv", f"两个协程同时 recv：{type(e).__name__}：{e}")
            t.cancel()

        async with connect(base + "/plain") as ws:
            await ws.send(json.dumps({"op": "silent", "id": 1}))
            t0 = time.monotonic()
            try:
                async with asyncio.timeout(1):
                    await ws.recv()
            except TimeoutError:
                out("timeout", f"服务端不回复：recv 本身没有超时，套上 asyncio.timeout(1) 后约 {round(time.monotonic() - t0)} 秒抛出 TimeoutError；连接仍是 {ws.state.name}")

        try:
            async with connect(base + "/secure"):
                pass
        except InvalidStatus as e:
            out("auth.missing", f"握手不带 Token：{type(e).__name__}，HTTP {e.response.status_code}；连接根本没有建立")
        async with connect(base + "/secure", additional_headers={"Authorization": f"Bearer {TOKEN}"}) as ws:
            await ws.send(json.dumps({"op": "echo", "id": 1, "text": "ok"}))
            out("auth.header", f"握手时在请求头带 Token：连接成功，收到 {json.loads(await ws.recv())['result']}")

        async with connect(base + "/plain") as ws:
            await ws.close()
            try:
                await ws.recv()
            except ConnectionClosedOK as e:
                out("close.normal", f"正常关闭后 recv：{type(e).__name__}，关闭码 {e.rcvd.code}")

        async with connect(base + "/plain") as ws:
            await ws.send(json.dumps({"op": "kick", "id": 1}))
            try:
                await ws.recv()
            except ConnectionClosedError as e:
                out("close.policy", f"服务端以 1008 踢掉连接：{type(e).__name__}，关闭码 {e.rcvd.code}，原因 {e.rcvd.reason!r}")

        async with connect(base + "/plain", max_size=None) as ws:
            big = json.dumps({"op": "echo", "id": 1, "text": "x" * (2 * 2**20)})
            await ws.send(big)
            try:
                await ws.recv()
            except ConnectionClosedError as e:
                out("close.too_big", f"发送 2 MiB 消息（服务端 max_size 1 MiB）：服务端以关闭码 {e.rcvd.code} 断开，原因 {e.rcvd.reason!r}")

        async with connect(base + "/plain") as ws:
            await ws.send(json.dumps({"op": "crash", "id": 1}))
            try:
                await ws.recv()
            except ConnectionClosedError as e:
                out("close.abnormal", f"服务端直接断开 TCP：{type(e).__name__}，没有收到关闭帧（rcvd={e.rcvd}）；RFC 6455 把这种情况记作 1006，它不会出现在关闭帧里")

        await asyncio.sleep(0.1)
        leaked = await connect(base + "/plain")
        await asyncio.sleep(0.1)
        out("cleanup.leak", f"测试里 connect 之后没有关闭：服务端仍有 {len(open_connections)} 个打开的连接")
        await leaked.close()
        await asyncio.sleep(0.1)
        out("cleanup.closed", f"显式 close（或用 async with）之后：服务端打开的连接 {len(open_connections)} 个")


asyncio.run(main())
