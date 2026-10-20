"""SDK 自带的重试与应用层重试叠加：用本地假服务计数，看一次业务调用实际发出了多少个请求。
假服务是标准库的 HTTP 服务器，按场景返回固定状态码；不调用任何真实模型服务。输出 TSV（键、说明）。"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import openai
from openai import OpenAI

state = {"mode": "503", "hits": 0, "retry_hdr": [], "done": 0}


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("content-length", 0)))
        state["hits"] += 1
        state["retry_hdr"].append(self.headers.get("x-stainless-retry-count"))
        mode = state["mode"]
        if mode == "slow":
            time.sleep(1.0)
            state["done"] += 1      # 客户端早已超时离开，服务端照样把这次请求处理完
        code = {"503": 503, "429": 429, "409": 409, "400": 400, "slow": 200, "noretry": 503}[mode]
        body = json.dumps({"error": {"message": mode, "type": "x"}}).encode()
        try:
            self.send_response(code)
            self.send_header("content-type", "application/json")
            if mode == "429":
                self.send_header("retry-after", "1")
            if mode == "noretry":
                self.send_header("x-should-retry", "false")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


srv = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{srv.server_address[1]}/v1"


def out(k, v):
    print(f"{k}\t{v}", flush=True)


def call(client):
    return client.chat.completions.create(model="demo-model", messages=[{"role": "user", "content": "hi"}])


def run(mode, client, app_retries=0):
    state.update(mode=mode, hits=0, retry_hdr=[], done=0)
    err = None
    t0 = time.monotonic()
    for attempt in range(app_retries + 1):
        try:
            call(client)
            break
        except openai.APIError as e:
            err = type(e).__name__
    return state["hits"], err, time.monotonic() - t0


out("env", f"openai={openai.__version__}")
out("defaults", f"DEFAULT_MAX_RETRIES={openai.DEFAULT_MAX_RETRIES}；DEFAULT_TIMEOUT={openai.DEFAULT_TIMEOUT}")
fast = dict(api_key="example_key", base_url=BASE)

c = OpenAI(**fast)
h, e, _ = run("503", c)
out("sdk.default", f"返回 503，只用 SDK 默认设置：一次调用发出 {h} 个请求，最后抛出 {e}；请求头 x-stainless-retry-count 依次为 {state['retry_hdr']}")
h, e, _ = run("503", c, app_retries=2)
out("stack", f"应用层再包一层「最多 3 次」的重试：一次业务调用发出 {h} 个请求")
c0 = OpenAI(max_retries=0, **fast)
h, e, _ = run("503", c0, app_retries=2)
out("sdk.zero", f"SDK max_retries=0，只保留应用层 3 次：{h} 个请求")
for mode in ("409", "400"):
    h, e, _ = run(mode, c)
    out(f"status.{mode}", f"返回 {mode}：{h} 个请求，抛出 {e}")
h, e, _ = run("noretry", c)
out("should_retry", f"返回 503 但带 x-should-retry: false：{h} 个请求")
h, e, dt = run("429", c)
out("retry_after", f"返回 429 且 retry-after: 1：{h} 个请求，总耗时不少于 2 秒：{dt >= 2.0}")
ct = OpenAI(timeout=0.3, **fast)
h, e, _ = run("slow", ct)
time.sleep(1.2)
out("timeout", f"服务端 1 秒才响应，SDK timeout=0.3：{h} 个请求都到达了服务端，最后抛出 {e}；客户端放弃之后，服务端处理完的有 {state['done']} 个")
srv.shutdown()
