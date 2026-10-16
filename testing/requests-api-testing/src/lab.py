"""用 requests 写接口测试时最容易出错的几处：查询参数的编码、data 与 json、默认没有超时、不检查状态码、会话。
同一个进程里起一个本地 HTTP 服务回显收到的请求，所有输入固定。"""
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import requests

PEERS = set()


class Echo(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, obj, extra=None):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(b)

    def _handle(self):
        PEERS.add(self.client_address[1])
        u = urlsplit(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode() if n else ""
        if u.path == "/slow":
            time.sleep(3)
        if u.path == "/fail":
            return self._send(500, {"error": "boom"})
        if u.path == "/login":
            return self._send(200, {"ok": True}, {"Set-Cookie": "sid=abc123; Path=/"})
        if u.path == "/me":
            return self._send(200 if "sid=abc123" in (self.headers.get("Cookie") or "") else 401, {"cookie": self.headers.get("Cookie")})
        self._send(200, {"query": parse_qs(u.query, keep_blank_values=True), "raw_query": u.query,
                         "content_type": self.headers.get("Content-Type"), "body": body})

    do_GET = do_POST = _handle


def out(k, v):
    print(f"{k}\t{v}")


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Echo)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    out("env", f"python={__import__('sys').version.split()[0]} requests={requests.__version__}")

    # 一、查询参数：自己拼字符串与交给 params
    r = requests.get(f"{base}/search?q=a b&c&tag=x").json()
    out("params.fstring", f"自己拼 URL「?q=a b&c」：服务端收到 {r['query']}")
    r = requests.get(f"{base}/search", params={"q": "a b&c", "tag": ["x", "y"]}).json()
    out("params.dict", f"params={{'q': 'a b&c', 'tag': ['x', 'y']}}：查询串 {r['raw_query']}，服务端收到 {r['query']}")

    # 二、data 与 json
    r = requests.post(f"{base}/orders", data={"sku": "A1", "qty": 2}).json()
    out("body.data_dict", f"data=dict：Content-Type {r['content_type']}，请求体 {r['body']}")
    r = requests.post(f"{base}/orders", json={"sku": "A1", "qty": 2}).json()
    out("body.json", f"json=dict：Content-Type {r['content_type']}，请求体 {r['body']}")
    r = requests.post(f"{base}/orders", data=json.dumps({"sku": "A1", "qty": 2})).json()
    out("body.data_str", f"data=json.dumps(...)：Content-Type {r['content_type']}，请求体 {r['body']}；没有声明 JSON，按 Content-Type 分派的服务端不会按 JSON 解析")
    r = requests.post(f"{base}/orders", data={"items": [{"sku": "A1"}, {"sku": "B2"}]}).json()
    out("body.data_nested", f"data 里放嵌套结构：请求体 {r['body']}")

    # 三、超时
    t = time.monotonic()
    requests.get(f"{base}/slow")
    out("timeout.none", f"不设 timeout 请求一个 3 秒才响应的接口：等了约 {round(time.monotonic() - t)} 秒；requests 默认不设超时，对方一直不响应就一直等")
    t = time.monotonic()
    try:
        requests.get(f"{base}/slow", timeout=1)
        res = "成功"
    except requests.exceptions.Timeout as e:
        res = type(e).__name__
    out("timeout.set", f"timeout=1：约 {round(time.monotonic() - t)} 秒后抛出 {res}")

    # 四、状态码
    resp = requests.get(f"{base}/fail")
    out("status.unchecked", f"500 响应：不抛异常，resp.ok={resp.ok}；只断言 resp.json() 有内容的测试照样通过")
    try:
        resp.raise_for_status()
        res = "无异常"
    except requests.exceptions.HTTPError as e:
        res = f"HTTPError：{str(e).split(' for url')[0]}"
    out("status.raise", f"raise_for_status()：{res}")

    # 五、会话
    requests.get(f"{base}/login")
    out("session.none", f"不用 Session：先登录再访问 /me，状态 {requests.get(f'{base}/me').status_code}")
    PEERS.clear()
    with requests.Session() as s:
        s.get(f"{base}/login")
        code = s.get(f"{base}/me").status_code
        for _ in range(5):
            s.get(f"{base}/search")
    out("session.cookie", f"Session：登录后访问 /me，状态 {code}；7 次请求用了 {len(PEERS)} 个 TCP 连接")
    PEERS.clear()
    for _ in range(7):
        requests.get(f"{base}/search")
    out("session.connections", f"不用 Session 发 7 次请求：用了 {len(PEERS)} 个 TCP 连接")
    srv.shutdown()


if __name__ == "__main__":
    main()
