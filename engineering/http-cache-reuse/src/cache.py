"""在 Chromium 里对本地源站发请求，统计每种缓存响应头下浏览器第二次是否回源、回源时带了什么验证头。"""
import time
from playwright.sync_api import sync_playwright
import origin

origin.start()
BASE = "http://127.0.0.1:8100"
def out(k, v): print(f"{k}\t{v}")
def hits(path): return [e for e in origin.log if e[0] == path]

with sync_playwright() as pw:
    browser = pw.chromium.launch()
    out("env", "chromium=" + browser.version)
    page = browser.new_page()
    page.goto(BASE + "/page")
    fetch = lambda p: page.evaluate("async (u) => { const r = await fetch(u); return r.status + ' ' + (await r.text()); }", BASE + p)

    for path, label in [("/max-age", "Cache-Control: max-age=600"), ("/nothing", "没有任何缓存相关的头"), ("/heuristic", "没有 Cache-Control，只有 Last-Modified（10 天前）"),
                        ("/no-store", "Cache-Control: no-store"), ("/no-cache", "Cache-Control: no-cache，带 ETag")]:
        first, second = fetch(path), fetch(path)
        h = hits(path)
        cond = "；第二次回源带 If-None-Match = " + str(h[1][1]) if len(h) > 1 and h[1][1] else ""
        out("twice" + path.replace("/", "."), f"{label}：连续 fetch 两次，源站收到 {len(h)} 次{cond}；页面第二次拿到「{second}」")

    fetch("/aged"); time.sleep(2); fetch("/aged")
    out("age", f"max-age=600 但响应带 Age: 599，取一次、等 2 秒再取：源站收到 {len(hits('/aged'))} 次")

    a = fetch("/changing"); b = fetch("/changing"); origin.version["/changing"] = 2; c = fetch("/changing")
    out("validate", f"no-cache 加 ETag，内容在第二次和第三次之间换了版本：三次拿到「{a}」「{b}」「{c}」；源站收到 {len(hits('/changing'))} 次，其中带验证头的 {sum(1 for e in hits('/changing') if e[1])} 次")

    before_js, before_imm = len(hits("/app.js")), len(hits("/app.immutable.js"))
    page.reload()
    out("reload", f"页面普通刷新：两个子资源都是 max-age=600，没标 immutable 的回源 {len(hits('/app.js')) - before_js} 次，标了 immutable 的回源 {len(hits('/app.immutable.js')) - before_imm} 次")
    browser.close()
