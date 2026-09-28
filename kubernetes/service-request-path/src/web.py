"""返回 Pod 名称的 HTTP 服务，支持 HTTP/1.1 长连接。"""
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NAME = socket.gethostname().encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(NAME)))
        self.end_headers()
        self.wfile.write(NAME)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("", 8080), Handler).serve_forever()
