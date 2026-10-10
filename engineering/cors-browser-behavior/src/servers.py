"""两个本地服务：8000 提供页面（页面的源），9000 是被调用的接口（另一个源）。接口的每个路径返回不同的跨源响应头，并记录收到的请求。"""
import json, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

received = []          # 接口实际收到的请求：(方法, 路径)
state = {"transfers": 0}
APP = "http://localhost:8000"

class Page(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        body = b"<!doctype html><title>app</title><p>page</p>"
        self.send_response(200); self.send_header("Content-Type", "text/html"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

class Api(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def cors(self):
        p = self.path.split("?")[0]
        h = {}
        if p in ("/star", "/star-cred"): h["Access-Control-Allow-Origin"] = "*"
        if p in ("/exact", "/exact-cred", "/json-ok", "/auth-ok", "/expose", "/cached"): h["Access-Control-Allow-Origin"] = APP
        if p in ("/exact-cred",): h["Access-Control-Allow-Credentials"] = "true"
        if p == "/expose": h["Access-Control-Expose-Headers"] = "X-Request-Id"
        return h
    def reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        for k, v in self.cors().items(): self.send_header(k, v)
        self.send_header("Content-Type", "application/json"); self.send_header("X-Request-Id", "req-42"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        received.append(("GET", self.path))
        if self.path == "/_log":
            received.pop(); return self.reply(200, {"received": received, "transfers": state["transfers"]})
        self.reply(200, {"ok": True, "cookie": self.headers.get("Cookie", "")})
    def do_POST(self):
        received.append(("POST", self.path))
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path.startswith("/transfer"): state["transfers"] += 1
        self.reply(200, {"ok": True})
    def do_OPTIONS(self):
        received.append(("OPTIONS", self.path))
        p = self.path
        if p in ("/json-ok", "/auth-ok", "/cached"):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", APP); self.send_header("Access-Control-Allow-Methods", "POST, GET")
            self.send_header("Access-Control-Allow-Headers", "content-type, authorization")
            if p == "/cached": self.send_header("Access-Control-Max-Age", "600")
            self.end_headers()
        else:
            self.send_response(405); self.send_header("Content-Length", "0"); self.end_headers()

def start():
    for port, handler in ((8000, Page), (9000, Api)):
        threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever, daemon=True).start()
