import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.text.Normalizer;

/** 与 uni.py、uni.js 同一组用例：字节、码点、UTF-16 单元、字形，以及截断、反转与规范化 */
public class Uni {
    static void out(String k, Object v) { System.out.println("java\t" + k + "\t" + v); }
    static int graphemes(String s) { BreakIterator b = BreakIterator.getCharacterInstance(); b.setText(s); int n = 0; while (b.next() != BreakIterator.DONE) n++; return n; }
    public static void main(String[] args) {
        out("version", System.getProperty("java.version"));
        String[][] cases = { { "zhong", "中" }, { "e_acute_nfc", "é" }, { "e_acute_nfd", "é" }, { "grin", "😀" }, { "family_zwj", "👨‍👩‍👧" }, { "flag_cn", "🇨🇳" }, { "thumbs_skin", "👍🏽" } };
        for (String[] c : cases) {
            String s = c[1];
            out("len." + c[0], "length()=" + s.length() + " codePoints=" + s.codePointCount(0, s.length()) + " utf8=" + s.getBytes(StandardCharsets.UTF_8).length + " graphemes=" + graphemes(s));
        }
        String grin = "😀";
        String cut = grin.substring(0, 1);
        out("cut.first_unit", "substring(0, 1) 得到 U+" + Integer.toHexString(cut.charAt(0)).toUpperCase() + "，是孤立代理：" + Character.isSurrogate(cut.charAt(0)) + "；编码成 UTF-8 后变成了 \"?\"：" + new String(cut.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals("?"));
        out("reverse.flag", "StringBuilder.reverse() 反转国旗：" + new StringBuilder("🇨🇳").reverse().toString().codePoints().mapToObj(Integer::toHexString).toList());
        out("equals.nfc_nfd", "é（预组合）equals e+重音：" + "é".equals("é") + "；NFC 规范化后：" + Normalizer.normalize("é", Normalizer.Form.NFC).equals("é"));
        out("upper.sharp_s", "\"straße\".toUpperCase()：" + "straße".toUpperCase() + "，长度 " + "straße".length() + " → " + "straße".toUpperCase().length());
    }
}
