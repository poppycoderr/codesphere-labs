"""在真实 Chromium 里拖动折线顶点：桌面宽度 DPR 1 与手机宽度 DPR 2 两种配置，检查预览与提交、取消、撤销重做、
拖出画布后的坐标限制，以及拖动期间外部更新导致的基准版本失效。输出 TSV（键、说明）。"""
import functools
import http.server
import sys
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

OUT = Path(sys.argv[1])
WIDTH, HEIGHT = 600, 300


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def out(key, text):
    print(f"{key}\t{text}", flush=True)


def fmt(points):
    return " ".join(f"{p['id']}({round(p['x'], 1):g},{round(p['y'], 1):g})" for p in points)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(QuietHandler, directory=str(Path(__file__).parent)))
threading.Thread(target=server.serve_forever, daemon=True).start()

with sync_playwright() as pw:
    browser = pw.chromium.launch()
    out("env", f"chromium={browser.version}")
    for width, dpr in ((900, 1), (390, 2)):
        tag = f"{width}px.dpr{dpr}"
        context = browser.new_context(viewport={"width": width, "height": 700}, device_scale_factor=dpr)
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(f"http://127.0.0.1:{server.server_port}/index.html")
        page.wait_for_function("window.editor")
        snap = lambda: page.evaluate("editor.snapshot()")
        box = page.locator("canvas").bounding_box()
        at = lambda lx, ly: (box["x"] + lx * box["width"] / WIDTH, box["y"] + ly * box["height"] / HEIGHT)
        mouse = page.mouse

        def grab(lx, ly):
            mouse.move(*at(lx, ly))
            mouse.down()

        s = snap()
        out(f"{tag}.canvas", f"画布显示宽度 {s['cssWidth']:g}px，像素缓冲宽度 {s['bufferWidth']}；初始 {fmt(s['points'])}，版本 {s['revision']}")

        grab(80, 80)
        mouse.move(*at(120, 110), steps=5)
        s = snap()
        out(f"{tag}.preview", f"拖动中：预览 {fmt(s['preview'][:1])}，正式模型 {fmt(s['points'][:1])}，版本 {s['revision']}")
        mouse.up()
        s = snap()
        committed = s["points"]
        out(f"{tag}.commit", f"松开：{s['lastOutcome']}，正式模型 {fmt(s['points'][:1])}，版本 {s['revision']}，撤销栈 {s['undo']}")

        page.locator("#undo").click()
        s = snap()
        out(f"{tag}.undo", f"撤销：{fmt(s['points'][:1])}，版本 {s['revision']}，重做栈 {s['redo']}")
        page.locator("#redo").click()
        s = snap()
        out(f"{tag}.redo", f"重做：{fmt(s['points'][:1])}，版本 {s['revision']}")

        grab(120, 110)
        mouse.move(*at(160, 150), steps=3)
        page.keyboard.press("Escape")
        mouse.up()
        s = snap()
        out(f"{tag}.escape", f"拖动中按 Esc：{s['lastOutcome']}，正式模型 {fmt(s['points'][:1])}，版本 {s['revision']}")

        grab(120, 110)
        mouse.move(*at(160, 150), steps=3)
        page.locator("canvas").dispatch_event("pointercancel", {"pointerId": 1})
        mouse.up()
        s = snap()
        out(f"{tag}.pointercancel", f"拖动中派发 pointercancel：{s['lastOutcome']}，正式模型 {fmt(s['points'][:1])}")

        grab(120, 110)
        mouse.move(box["x"] - 30, at(0, 140)[1], steps=4)          # 拖到画布左边之外
        mouse.up()
        s = snap()
        out(f"{tag}.outside", f"拖到画布左侧之外再松开：{s['lastOutcome']}，{fmt(s['points'][:1])}（x 限制在 0）")

        grab(240, 160)
        mouse.move(*at(260, 200), steps=3)
        page.evaluate("editor.applyExternal(editor.snapshot().points.map(p => p.id === 'p3' ? {...p, y: 40} : p))")
        mouse.up()
        s = snap()
        out(f"{tag}.stale", f"拖动 p2 期间别处把 p3 改到 y=40：松开时 {s['lastOutcome']}，结果 {fmt(s['points'])}")

        page.locator("#undo").click()
        s = snap()
        before = s["redo"]
        out(f"{tag}.undo_external", f"随后撤销一次：{fmt(s['points'])}（快照式撤销把别处对 p3 的修改也撤掉了）")
        grab(240, 160)
        mouse.move(*at(250, 170), steps=2)
        mouse.up()
        s = snap()
        out(f"{tag}.branch", f"撤销后重做栈 {before} 条，再提交一次新拖动：{s['lastOutcome']}，重做栈 {s['redo']} 条；顶点 ID {[p['id'] for p in s['points']]}")
        page.screenshot(path=str(OUT / f"editor-{width}-dpr{dpr}.png"), full_page=True)

        # 对照：命令式撤销只还原这次改动过的顶点
        page.goto(f"http://127.0.0.1:{server.server_port}/index.html?history=command")
        page.wait_for_function("window.editor")
        grab(80, 80)
        mouse.move(*at(120, 110), steps=3)
        mouse.up()
        page.evaluate("editor.applyExternal(editor.snapshot().points.map(p => p.id === 'p3' ? {...p, y: 40} : p))")
        page.locator("#undo").click()
        s = snap()
        out(f"{tag}.command_undo", f"命令式撤销：提交 p1 的拖动、别处改 p3 之后撤销：{s['lastOutcome']}，{fmt(s['points'])}")
        grab(80, 80)
        mouse.move(*at(100, 100), steps=3)
        mouse.up()
        page.evaluate("editor.applyExternal(editor.snapshot().points.map(p => p.id === 'p1' ? {...p, x: 300} : p))")
        page.locator("#undo").click()
        s = snap()
        out(f"{tag}.command_conflict", f"命令式撤销：提交 p1 的拖动、别处又改了 p1 之后撤销：{s['lastOutcome']}，{fmt(s['points'][:1])}（不覆盖别人的修改）")
        out(f"{tag}.errors", f"页面错误 {len(errors)} 个")
        context.close()
    browser.close()
server.shutdown()
