import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与 escape.py、escape.js 同一组用例：源码字符串、正则、替换模板三层转义 */
public class Escape {
    static void out(String k, Object v) { System.out.println("java\t" + k + "\t" + v); }
    static String tryIt(java.util.concurrent.Callable<Object> c) { try { return String.valueOf(c.call()); } catch (Exception e) { return "异常 " + e.getClass().getSimpleName() + "：" + e.getMessage(); } }
    public static void main(String[] args) {
        String path = "C:\\temp\\a.txt";                       // 源码里两个反斜杠才是一个
        out("source.length", path + "，长度 " + path.length());
        out("backslash.regex_source", "\"\\\\\\\\\"（源码 4 个反斜杠 → 字符串 2 个 → 正则匹配 1 个）：" + path.replaceAll("\\\\", "/"));
        out("backslash.literal_replace", "String.replace 不用正则：" + path.replace("\\", "/"));
        out("dot.unescaped", "a.b 匹配 axb：" + Pattern.compile("a.b").matcher("axb").matches());
        out("dot.quoted", "Pattern.quote(\"a.b\") 匹配 axb：" + Pattern.compile(Pattern.quote("a.b")).matcher("axb").matches());
        String price = "$5";
        out("replacement.dollar_raw", "把 {price} 替换成用户输入的 \"$5\"：" + tryIt(() -> "价格 {price}".replaceAll("\\{price\\}", price)));
        out("replacement.dollar_quoted", "Matcher.quoteReplacement：" + "价格 {price}".replaceAll("\\{price\\}", Matcher.quoteReplacement(price)));
        out("replacement.backslash_raw", "替换串是一个反斜杠（源码写 \"\\\\\"）：" + tryIt(() -> "a/b".replaceAll("/", "\\")));
        out("replacement.backslash_escaped", "替换串是两个反斜杠（源码写 \"\\\\\\\\\"）：" + "a/b".replaceAll("/", "\\\\"));
        out("replacement.backslash_quoted", "Matcher.quoteReplacement(\"\\\\\")：" + "a/b".replaceAll("/", Matcher.quoteReplacement("\\")));
        out("textblock.backslash", """
                文本块里的 \\d 仍是转义：""".strip() + Pattern.compile("""
                \\d+""".strip()).matcher("42").matches());
    }
}
