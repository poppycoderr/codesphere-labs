"""与 Escape.java、escape.js 同一组用例"""
import re


def out(k, v):
    print(f"python\t{k}\t{v}")


def try_it(f):
    try:
        return f()
    except Exception as e:
        return f"异常 {type(e).__name__}：{e}"


path = "C:\\temp\\a.txt"
out("source.length", f"{path}，长度 {len(path)}")
import warnings
with warnings.catch_warnings(record=True) as w:
    warnings.simplefilter("always")
    v = eval(compile('"\\d"', "<case>", "eval"))
out("source.non_raw_warning", f"非原始字符串 \"\\d\"：长度 {len(v)}，编译时警告 {[x.category.__name__ for x in w]}")
out("backslash.regex_source", "r\"\\\\\"（原始字符串 2 个反斜杠 → 正则匹配 1 个）：" + re.sub(r"\\", "/", path))
out("backslash.literal_replace", "str.replace 不用正则：" + path.replace("\\", "/"))
out("dot.unescaped", f"a.b 匹配 axb：{bool(re.fullmatch('a.b', 'axb'))}")
out("dot.quoted", f"re.escape('a.b') 匹配 axb：{bool(re.fullmatch(re.escape('a.b'), 'axb'))}")
out("replacement.backslash_user", "把 {price} 替换成用户输入的 \"\\5\"：" + try_it(lambda: re.sub(r"\{price\}", "\\5", "价格 {price}")))
out("replacement.function", "替换参数传函数：" + re.sub(r"\{price\}", lambda m: "\\5", "价格 {price}"))
out("replacement.dollar_raw", "替换成 \"$5\"：" + re.sub(r"\{price\}", "$5", "价格 {price}"))
