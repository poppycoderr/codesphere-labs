import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与 regex.py、regex.js 同一组用例：每行输出「用例名、结果」 */
public class Regex {
    static void out(String k, Object v) { System.out.println("java\t" + k + "\t" + v); }
    static String tryIt(java.util.concurrent.Callable<Object> c) { try { return String.valueOf(c.call()); } catch (Exception e) { return "异常 " + e.getClass().getSimpleName(); } }
    public static void main(String[] args) {
        out("version", System.getProperty("java.version"));
        out("digit.arabic_indic", Pattern.compile("^\\d+$").matcher("١٢٣").find());
        out("digit.fullwidth", Pattern.compile("^\\d+$").matcher("１２３").find());
        out("word.chinese", Pattern.compile("^\\w+$").matcher("中文").find());
        out("dollar.trailing_newline", Pattern.compile("abc$").matcher("abc\n").find());
        out("dot.newline", Pattern.compile("a.b").matcher("a\nb").find());
        out("whole.match_api", Pattern.compile("\\d{3}").matcher("1234").matches() + "（matches）/ " + Pattern.compile("\\d{3}").matcher("1234").find() + "（find）");
        out("named.syntax_java_style", tryIt(() -> { Matcher m = Pattern.compile("(?<y>\\d{4})-(?<m>\\d{2})").matcher("2026-10"); m.find(); return m.group("y"); }));
        out("replace.numbered", "2026-10".replaceAll("(\\d{4})-(\\d{2})", "$2/$1"));
        out("replace.named", "2026-10".replaceAll("(?<y>\\d{4})-(?<m>\\d{2})", "${m}/${y}"));
        out("replace.backslash_template", tryIt(() -> "2026-10".replaceAll("(\\d{4})-(\\d{2})", "\\2/\\1")));
        out("split.trailing_empty", Arrays.toString("a,b,,".split(",")));
        out("split.dot", Arrays.toString("a.b.c".split(".")));
        out("case.insensitive_unicode", Pattern.compile("straße", Pattern.CASE_INSENSITIVE).matcher("STRASSE").find() + " / " + Pattern.compile("é", Pattern.CASE_INSENSITIVE).matcher("É").find());
        out("case.insensitive_unicode_case", Pattern.compile("é", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher("É").find() + "（加 UNICODE_CASE）");
        out("digit.unicode_character_class", Pattern.compile("^\\d+$", Pattern.UNICODE_CHARACTER_CLASS).matcher("１２３").find() + "（加 UNICODE_CHARACTER_CLASS）");
    }
}
