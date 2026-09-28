"""被发布的服务：VERSION 标识版本，START_DELAY 模拟启动预热，REQUIRE_DB_URL 模拟新版本依赖的新配置，DEP 为下游地址。"""
import os
import signal
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = os.environ.get("VERSION", "v1")
START_DELAY = float(os.environ.get("START_DELAY", "0"))
REQUIRE_DB_URL = os.environ.get("REQUIRE_DB_URL") == "true"
DEP = os.environ.get("DEP", "")


def config_ok():
    return not REQUIRE_DB_URL or bool(os.environ.get("DB_URL"))


def dep_ok():
    if not DEP:
        return True
    try:
        with urllib.request.urlopen(DEP, timeout=0.5) as r:
            return r.status == 200
    except OSError:
        return False


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, body):
        data = (body + "\n").encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            self.reply(200, "alive")
        elif self.path == "/ready":
            self.reply(200, "ready") if config_ok() else self.reply(503, "missing DB_URL")
        elif self.path == "/ready-with-dep":
            self.reply(200, "ready") if config_ok() and dep_ok() else self.reply(503, "dependency down")
        elif not config_ok():
            self.reply(500, VERSION + " missing DB_URL")
        elif not dep_ok():
            self.reply(503, "dependency down")
        else:
            self.reply(200, VERSION)

    def log_message(self, *args):
        pass


def on_sigterm(*_):
    # 作为 PID 1 时不注册处理函数，SIGTERM 会被忽略；这里模拟常见应用：收到 SIGTERM 立即停止监听并退出
    print(f"{VERSION} got SIGTERM, exiting", flush=True)
    os._exit(0)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, on_sigterm)
    print(f"{VERSION} starting, warm-up {START_DELAY}s", flush=True)
    time.sleep(START_DELAY)
    server = ThreadingHTTPServer(("", 8080), Handler)
    print(f"{VERSION} listening", flush=True)
    sys.stdout.flush()
    server.serve_forever()
