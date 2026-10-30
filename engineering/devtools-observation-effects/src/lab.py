"""用 Chrome DevTools 协议驱动调试器，观察「观察」本身对程序和证据的影响。每个场景输出一行。"""
import asyncio
import json
import sys
import urllib.request

sys.path.insert(0, "/w/src")
from playwright.async_api import async_playwright  # noqa: E402
import server  # noqa: E402

BASE = "http://127.0.0.1:8765"


def out(key, text):
    print(f"{key}\t{text}", flush=True)


async def main():
    server.start()
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page()
        cdp = await page.context.new_cdp_session(page)
        await cdp.send("Runtime.enable")
        await cdp.send("Debugger.enable")
        await cdp.send("Profiler.enable")
        await cdp.send("Profiler.startPreciseCoverage", {"callCount": True, "detailed": False})
        logged = []
        cdp.on("Runtime.consoleAPICalled", lambda e: logged.append(e))
        paused = []

        async def on_pause(e):
            paused.append(e)
            await asyncio.sleep(1.0)               # 有人盯着断点看了一秒
            await cdp.send("Debugger.resume")

        cdp.on("Debugger.paused", lambda e: asyncio.ensure_future(on_pause(e)))
        await page.goto(BASE + "/")

        # 一、控制台打印的是引用
        await page.evaluate("logCart()")
        await asyncio.sleep(0.2)
        arg = logged[-1]["args"][0]
        preview = {pp["name"]: pp["value"] for pp in arg["preview"]["properties"]}
        props = await cdp.send("Runtime.getProperties", {"objectId": arg["objectId"], "ownProperties": True})
        total_now = next(pp["value"]["value"] for pp in props["result"] if pp["name"] == "total")
        items = next(pp["value"] for pp in props["result"] if pp["name"] == "items")
        items_props = await cdp.send("Runtime.getProperties", {"objectId": items["objectId"], "ownProperties": True})
        n_items = len([pp for pp in items_props["result"] if pp["name"].isdigit()])
        out("console.ref", f"console.log(cart) 之后代码把 total 改成 4、items 加了一项：日志里折叠的预览 total={preview['total']}（打印那一刻的摘要）；展开对象读到 total={total_now}、items 有 {n_items} 项（展开时的当前值）")
        snap = await page.evaluate("JSON.stringify({a: undefined, f() {}, n: NaN, d: new Date(0)})")
        big = await page.evaluate("(() => { try { return JSON.stringify({id: 10n}) } catch (e) { return e.name } })()")
        cyc = await page.evaluate("(() => { const o = {}; o.self = o; try { return JSON.stringify(o) } catch (e) { return e.name } })()")
        out("console.snapshot", f"用 JSON.stringify 留快照：{{a: undefined, f() {{}}, n: NaN, d: Date}} 变成 {snap}；BigInt 抛 {big}，循环引用抛 {cyc}")

        # 二、条件断点的表达式会执行
        await page.evaluate("window.cart = {items: ['a', 'b', 'c'], total: 3}")
        await cdp.send("Debugger.setBreakpointByUrl", {"urlRegex": "app\\.js", "lineNumber": 10, "condition": "window.cart.items.pop(), false"})
        r1 = await page.evaluate("checkout()")
        r2 = await page.evaluate("checkout()")
        out("bp.condition", f"在 checkout 里设条件断点，条件写成 items.pop(), false（想「看一下」又不想暂停）：断点一次也没暂停={len(paused) == 0}；两次 checkout 依次返回 {r1}、{r2}，购物车剩 {await page.evaluate('window.cart.items.length')} 项")
        await cdp.send("Debugger.disable")
        await cdp.send("Debugger.enable")

        # 三、在断点上停留会改变时间
        normal = await page.evaluate("slowStep()")
        await cdp.send("Debugger.setBreakpointByUrl", {"urlRegex": "app\\.js", "lineNumber": 16})
        slowed = await page.evaluate("slowStep()")
        out("bp.timing", f"slowStep 里超过 500ms 就走降级分支：不调试时返回「{normal}」；在中间行停 1 秒后返回「{slowed}」，暂停 {len(paused)} 次")
        await cdp.send("Debugger.disable")

        # 四、重放请求就是再执行一次
        await cdp.send("Network.enable")
        reqs = []
        cdp.on("Network.requestWillBeSent", lambda e: reqs.append(e) if e["request"]["method"] == "POST" else None)
        await page.evaluate("placeOrder()")
        await asyncio.sleep(0.3)
        await cdp.send("Network.replayXHR", {"requestId": reqs[0]["requestId"]})
        how = "Network.replayXHR，即网络面板的 Replay XHR"
        await asyncio.sleep(0.5)
        count = urllib.request.urlopen(BASE + "/count").read().decode()
        out("replay", f"页面下单一次后在网络面板重放这个 POST（{how}）：服务端收到下单 {count} 次")

        # 五、覆盖率只反映这一次走过的路径
        cov1 = await cdp.send("Profiler.takePreciseCoverage")
        await page.click("#export")
        cov2 = await cdp.send("Profiler.takePreciseCoverage")

        def called(cov, name):
            for s in cov["result"]:
                if s["url"].endswith("app.js"):
                    for fn in s["functions"]:
                        if fn["functionName"] == name:
                            return sum(r["count"] for r in fn["ranges"][:1])
            return 0
        out("coverage", f"加载与前面几个场景之后，handleExport 执行 {called(cov1, 'handleExport')} 次（覆盖率工具标为未执行）；点击「导出」后 {called(cov2, 'handleExport')} 次；neverCalled 两次都是 {called(cov2, 'neverCalled')} 次，但它可能只在别的路径上被调用")
        out("env", f"chromium {browser.version}")
        await browser.close()


asyncio.run(main())
