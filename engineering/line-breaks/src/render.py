"""软换行在渲染后的样子：markdown-it 把段落内的换行原样输出为换行符，三种浏览器把它显示成什么。"""
import asyncio
from markdown_it import MarkdownIt
from playwright.async_api import async_playwright

md = MarkdownIt("commonmark")
src = "中文段落的第一行\n接着写第二行\n\nfirst line of text\nsecond line\n"
html = md.render(src)
print("markdown\t" + "markdown-it-py 4.2.0（CommonMark）渲染两段软换行的结果：" + html.replace("\n", "⏎"))

PAGE = """<meta charset=utf-8><style>p{font:16px monospace;margin:0;white-space:normal}</style>
<p id=zh_nl>中文\n中文</p><p id=zh>中文中文</p><p id=zh_sp>中文 中文</p>
<p id=en_nl>abc\ndef</p><p id=en>abcdef</p><p id=en_sp>abc def</p>"""


async def main():
    async with async_playwright() as pw:
        for name in ["chromium", "firefox", "webkit"]:
            b = await getattr(pw, name).launch()
            p = await b.new_page()
            await p.set_content(PAGE)
            w = {i: await p.eval_on_selector(f"#{i}", "e => { const r = document.createRange(); r.selectNodeContents(e); return Math.round(r.getBoundingClientRect().width) }")
                 for i in ["zh_nl", "zh", "zh_sp", "en_nl", "en", "en_sp"]}
            zh = "显示成空格" if w["zh_nl"] == w["zh_sp"] else ("不显示" if w["zh_nl"] == w["zh"] else "其他")
            en = "显示成空格" if w["en_nl"] == w["en_sp"] else ("不显示" if w["en_nl"] == w["en"] else "其他")
            print(f"browser.{name}\t{name} {b.version}：两个汉字之间的换行{zh}，两个英文单词之间的换行{en}")
            await b.close()

asyncio.run(main())
