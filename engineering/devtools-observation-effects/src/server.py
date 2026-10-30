"""本地页面与一个会记账的 POST 接口；GET /count 返回收到的下单次数。"""
import http.server
import threading

ORDERS = {"n": 0}


class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory="/w/src", **k)

    def do_POST(self):
        ORDERS["n"] += 1
        self.send_response(201)
        self.end_headers()

    def do_GET(self):
        if self.path == "/count":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(str(ORDERS["n"]).encode())
            return
        if self.path == "/":
            self.path = "/page.html"
        super().do_GET()

    def log_message(self, *a):
        pass


def start():
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), H)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s
