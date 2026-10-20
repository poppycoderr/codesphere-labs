"""流式响应的生命周期：SSE 分帧、客户端中途断开后服务端还在不在生成、生成到一半出错时客户端看到什么。
服务端是进程内启动的 FastAPI + uvicorn，生成器是按固定间隔吐出 token 的假实现，不调用任何模型。输出 TSV（键、说明）。"""
import asyncio
import threading
import time

import fastapi
import httpx
import uvicorn
from fastapi import FastAPI
from fastapi.responses import StreamingResponse

app = FastAPI()
stats = {}


def track(name):
    stats[name] = {"produced": 0, "finally": False, "cancelled": False}
    return stats[name]


@app.get("/async")
async def async_stream():
    s = track("async")

    async def gen():
        try:
            for i in range(20):
                await asyncio.sleep(0.05)
                s["produced"] += 1
                yield f"data: token-{i}\n\n"
        except asyncio.CancelledError:
            s["cancelled"] = True
            raise
        finally:
            s["finally"] = True
    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/sync")
def sync_stream():
    s = track("sync")

    def gen():
        try:
            for i in range(20):
                time.sleep(0.05)       # 阻塞调用：Starlette 把同步生成器放进线程池执行
                s["produced"] += 1
                yield f"data: token-{i}\n\n"
        finally:
            s["finally"] = True
    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/fail")
async def failing():
    async def gen():
        yield "data: token-0\n\n"
        yield "data: token-1\n\n"
        raise RuntimeError("推理后端在第 3 个 token 时失败")
    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/fail-explicit")
async def failing_explicit():
    async def gen():
        try:
            yield "data: token-0\n\n"
            yield "data: token-1\n\n"
            raise RuntimeError("推理后端在第 3 个 token 时失败")
        except RuntimeError:
            yield 'event: error\ndata: {"code": "backend_failed", "partial": true}\n\n'
            return
    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/frames")
async def frames():
    async def gen():
        yield "data: 第一段\n"          # 少了空行：这一行和下一条合成一个事件
        yield "data: 第二段\n\n"
        yield "data: 多行内容第一行\ndata: 多行内容第二行\n\n"
        yield "event: done\ndata: {}\n\n"
    return StreamingResponse(gen(), media_type="text/event-stream")


def parse_sse(lines):
    """按 SSE 规则解析：空行结束一个事件，多行 data 用换行拼接"""
    events, data, event = [], [], "message"
    for line in lines:
        if line == "":
            if data:
                events.append((event, "\n".join(data)))
            data, event = [], "message"
        elif line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
        elif line.startswith("event:"):
            event = line[6:].strip()
    return events


def out(k, v):
    print(f"{k}\t{v}", flush=True)


async def main(base):
    async with httpx.AsyncClient(base_url=base, timeout=10) as c:
        async with c.stream("GET", "/frames") as r:
            lines = [l async for l in r.aiter_lines()]
        evs = parse_sse(lines)
        out("frames", f"服务端写了 4 段，客户端按 SSE 规则解析出 {len(evs)} 个事件：" + " | ".join(f"{e}:{d!r}" for e, d in evs))

        for path in ("async", "sync"):
            got = 0
            async with c.stream("GET", "/" + path) as r:
                async for line in r.aiter_lines():
                    if line.startswith("data:"):
                        got += 1
                    if got == 3:
                        break        # 客户端读到 3 个 token 后离开
            at_close = stats[path]["produced"]
            await asyncio.sleep(1.5)
            s = stats[path]
            out(f"disconnect.{path}", f"{'异步' if path == 'async' else '同步（线程池）'}生成器，客户端读到 3 个后断开：断开时服务端已生成 {at_close} 个，1.5 秒后共 {s['produced']} 个（共 20 个）；"
                + f"finally 执行={s['finally']}" + (f"，收到 CancelledError={s['cancelled']}" if path == "async" else ""))

        async with c.stream("GET", "/fail") as r:
            status = r.status_code
            got, err = [], None
            try:
                async for line in r.aiter_lines():
                    if line:
                        got.append(line)
            except httpx.HTTPError as e:
                err = type(e).__name__
        out("fail.implicit", f"生成到一半抛异常：状态码 {status}，客户端收到 {got}，读取时的异常 {err}")
        async with c.stream("GET", "/fail-explicit") as r:
            evs = parse_sse([l async for l in r.aiter_lines()])
        out("fail.explicit", f"把失败写成一个错误事件：状态码 {r.status_code}，事件 {evs}")


def run():
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=8765, log_level="critical"))
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    while not server.started:
        time.sleep(0.05)
    out("env", f"fastapi={fastapi.__version__} uvicorn={uvicorn.__version__} httpx={httpx.__version__}")
    asyncio.run(main("http://127.0.0.1:8765"))
    server.should_exit = True
    t.join(5)


run()
