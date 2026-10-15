"""本地源站：每个路径返回不同的缓存相关响应头，并记录每次收到的请求及其中的验证头。"""
import threading, time
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = []                 # (路径, If-None-Match, If-Modified-Since)
version = {"/changing": 1}
TEN_DAYS_AGO = formatdate(time.time() - 10 * 86400, usegmt=True)

ROUTES = {
    "/max-age":     {"Cache-Control": "max-age=600"},
    "/no-cache":    {"Cache-Control": "no-cache", "ETag": '"v1"'},
    "/no-store":    {"Cache-Control": "no-store"},
    "/heuristic":   {"Last-Modified": TEN_DAYS_AGO},
    "/nothing":     {},
    "/aged":        {"Cache-Control": "max-age=600", "Age": "599"},
    "/changing":    {"Cache-Control": "no-cache"},
    "/app.js":      {"Cache-Control": "max-age=600", "Content-Type": "text/javascript"},
    "/app.immutable.js": {"Cache-Control": "max-age=600, immutable", "Content-Type": "text/javascript"},
}

class Origin(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/_log":
            body = repr(log).encode(); return self.send(200, {"Cache-Control": "no-store"}, body)
        if path == "/page":
            body = b'<!doctype html><title>p</title><script src="/app.js"></script><script src="/app.immutable.js"></script>'
            return self.send(200, {"Cache-Control": "no-cache", "Content-Type": "text/html"}, body)
        inm, ims = self.headers.get("If-None-Match"), self.headers.get("If-Modified-Since")
        log.append((path, inm, ims))
        headers = dict(ROUTES.get(path, {}))
        if path == "/changing":
            tag = f'"r{version[path]}"'; headers["ETag"] = tag
            if inm == tag: return self.send(304, headers, b"")
            return self.send(200, headers, f"release {version[path]}".encode())
        if path == "/no-cache" and inm == '"v1"': return self.send(304, headers, b"")
        body = b"// js" if path.endswith(".js") else f"body of {path}".encode()
        self.send(200, headers, body)
    def send(self, code, headers, body):
        self.send_response(code)
        for k, v in headers.items(): self.send_header(k, v)
        self.send_header("Date", formatdate(usegmt=True))
        if code != 304: self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if code != 304: self.wfile.write(body)

def start():
    threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", 8100), Origin).serve_forever, daemon=True).start()
