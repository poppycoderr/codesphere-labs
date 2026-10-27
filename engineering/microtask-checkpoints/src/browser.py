"""Chromium 部分：真实点击与脚本调用 click() 时，两个监听器之间是否清空微任务；各队列的顺序。"""
import http.server, threading, functools, os
from playwright.sync_api import sync_playwright

class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


handler = functools.partial(Quiet, directory=os.path.join(os.path.dirname(__file__)))
srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_page()
    page.goto(f"http://127.0.0.1:{srv.server_port}/page.html")
    print(f"browser.version\tchromium={b.version}")
    page.click("#btn")
    page.wait_for_timeout(50)
    print("browser.user-click\t用户点击（真实输入事件）：" + " → ".join(page.evaluate("log")))
    page.evaluate("log.length = 0; document.getElementById('btn').click()")
    page.wait_for_timeout(50)
    print("browser.script-click\t脚本里调用 btn.click()：" + " → ".join(page.evaluate("log")))
    print("browser.order\t" + page.evaluate("order()"))
    b.close()
srv.shutdown()
