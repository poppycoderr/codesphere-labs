"""在真实的 Chromium 里从 http://localhost:8000 的页面请求 http://localhost:9000 的接口，记录页面脚本拿到的结果，并与接口实际收到的请求对照。"""
import json, urllib.request
from playwright.sync_api import sync_playwright
import servers

servers.start()
API = "http://localhost:9000"
def out(k, v): print(f"{k}\t{v}")
def server_log(): return json.load(urllib.request.urlopen(API + "/_log"))
def seen(log, method, path): return sum(1 for m, p in log["received"] if m == method and p == path)

FETCH = """async ([url, init]) => {
  try { const r = await fetch(url, init); const t = await r.text(); return '读到响应（状态 ' + r.status + '），X-Request-Id = ' + r.headers.get('X-Request-Id'); }
  catch (e) { return '脚本拿到 ' + e.name + '，读不到任何内容'; }
}"""

with sync_playwright() as pw:
    browser = pw.chromium.launch()
    out("env", "chromium=" + browser.version)
    page = browser.new_page()
    console = []
    page.on("console", lambda m: console.append(m.text) if m.type == "error" else None)
    page.goto("http://localhost:8000/")
    run = lambda path, init=None: page.evaluate(FETCH, [API + path, init or {}])

    out("tool.direct", "不经过浏览器，用脚本直接请求同一个接口（没有任何跨源头）：" + urllib.request.urlopen(API + "/direct").read().decode())
    r = run("/plain"); log = server_log()
    out("get.no_header", f"页面里 fetch 这个接口：{r}；接口收到这个 GET {seen(log, 'GET', '/plain')} 次")
    out("get.console", "浏览器控制台的报错里提到 Access-Control-Allow-Origin = " + str(any("Access-Control-Allow-Origin" in c for c in console)))

    r = run("/transfer", {"method": "POST", "headers": {"Content-Type": "text/plain"}, "body": "to=bob&amount=100"}); log = server_log()
    out("post.simple", f"页面里发一个 text/plain 的 POST 到 /transfer（没有跨源头）：{r}；接口收到 POST {seen(log, 'POST', '/transfer')} 次，转账次数 = {log['transfers']}")

    r = run("/transfer-json", {"method": "POST", "headers": {"Content-Type": "application/json"}, "body": "{}"}); log = server_log()
    out("post.json", f"换成 application/json（接口不处理 OPTIONS）：{r}；接口收到 OPTIONS {seen(log, 'OPTIONS', '/transfer-json')} 次、POST {seen(log, 'POST', '/transfer-json')} 次")
    r = run("/json-ok", {"method": "POST", "headers": {"Content-Type": "application/json"}, "body": "{}"}); log = server_log()
    out("post.json_ok", f"接口正确应答预检并带上跨源头：{r}；接口收到 OPTIONS {seen(log, 'OPTIONS', '/json-ok')} 次、POST {seen(log, 'POST', '/json-ok')} 次")

    out("allow.star", "响应带 Access-Control-Allow-Origin: *：" + run("/star"))
    out("allow.expose", "响应另外带 Access-Control-Expose-Headers: X-Request-Id：" + run("/expose"))

    page.context.add_cookies([{"name": "session", "value": "s1", "url": API}])
    out("cred.star", "请求带上 Cookie（credentials: include），响应是 Allow-Origin: *：" + run("/star-cred", {"credentials": "include"}))
    out("cred.exact_only", "响应写明了源，但没有 Allow-Credentials：" + run("/exact", {"credentials": "include"}))
    out("cred.exact", "响应写明了源，并带 Allow-Credentials: true：" + run("/exact-cred", {"credentials": "include"}))
    log = server_log()
    out("cred.sent", f"上面三次请求，接口都收到了 = {all(seen(log, 'GET', p) == 1 for p in ('/star-cred', '/exact', '/exact-cred'))}")

    run("/cached", {"method": "POST", "headers": {"Content-Type": "application/json"}, "body": "{}"}); run("/cached", {"method": "POST", "headers": {"Content-Type": "application/json"}, "body": "{}"}); log = server_log()
    out("preflight.cache", f"预检响应带 Access-Control-Max-Age: 600，连续发两次 POST：接口收到 OPTIONS {seen(log, 'OPTIONS', '/cached')} 次、POST {seen(log, 'POST', '/cached')} 次")
    browser.close()
