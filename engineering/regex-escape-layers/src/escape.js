// 与 Escape.java、escape.py 同一组用例
const out = (k, v) => console.log(`node\t${k}\t${v}`);
const path = "C:\\temp\\a.txt";
out("source.length", `${path}，长度 ${path.length}`);
out("backslash.regex_source", "/\\\\/g（字面量里 2 个反斜杠 → 匹配 1 个）：" + path.replace(/\\/g, "/"));
out("backslash.literal_replace", "replace 传字符串只替换第一个：" + path.replace("\\", "/") + "；replaceAll：" + path.replaceAll("\\", "/"));
out("backslash.new_regexp", 'new RegExp("\\\\\\\\")（源码 4 个反斜杠）：' + path.replace(new RegExp("\\\\", "g"), "/"));
out("dot.unescaped", `a.b 匹配 axb：${/^a.b$/.test("axb")}`);
out("dot.quoted", `RegExp.escape("a.b") 匹配 axb：${typeof RegExp.escape === "function" ? new RegExp("^" + RegExp.escape("a.b") + "$").test("axb") : "不支持 RegExp.escape"}`);
out("replacement.dollar_raw", '把 {price} 替换成用户输入的 "$5"：' + "价格 {price}".replace(/\{price\}/, "$5"));
out("replacement.dollar_amp", '用户输入 "$&"：' + "价格 {price}".replace(/\{price\}/, "$&"));
out("replacement.function", '替换参数传函数，返回 "$&"：' + "价格 {price}".replace(/\{price\}/, () => "$&"));
