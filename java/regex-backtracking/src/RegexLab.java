import java.util.regex.Pattern;

/**
 * 正则回溯：用一个记录 charAt 调用次数的 CharSequence 包住输入，把「匹配做了多少工作」变成确定的数字。
 * 同一个表达式分别喂能匹配的输入和「差一点就匹配」的输入，看次数随长度怎样增长。
 * 代码只用 Java 8 的语法，以便在 JDK 8 与 JDK 25 上各跑一遍做对照。
 */
public class RegexLab {
    /** 记录 charAt 次数；超过预算时抛异常 */
    static final class Counting implements CharSequence {
        final String s; long calls; final long budget;
        Counting(String s, long budget) { this.s = s; this.budget = budget; }
        public int length() { return s.length(); }
        public char charAt(int i) {
            if (++calls > budget) throw new IllegalStateException("regex budget exceeded");
            return s.charAt(i);
        }
        public CharSequence subSequence(int a, int b) { return s.subSequence(a, b); }
        @Override public String toString() { return s; }
    }

    interface Input { String of(int n); }

    static final long CAP = 200_000_000L;

    static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    static String run(Pattern p, String input, boolean find) {
        Counting c = new Counting(input, CAP);
        try {
            boolean ok = find ? p.matcher(c).find() : p.matcher(c).matches();
            return (ok ? "匹配" : "不匹配") + " " + c.calls;
        } catch (IllegalStateException e) {
            return "超过 " + CAP;
        }
    }

    static void series(String key, String regex, int[] lengths, Input input, boolean find) {
        Pattern p = Pattern.compile(regex);
        StringBuilder sb = new StringBuilder(regex);
        for (int n : lengths) sb.append("\tn=").append(n).append(": ").append(run(p, input.of(n), find));
        System.out.println(key + "\t" + sb);
    }

    public static void main(String[] args) {
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        int[] ns = {10, 14, 18, 22, 26};
        Input aBang = new Input() { public String of(int n) { return rep("a", n) + "!"; } };
        Input aB = new Input() { public String of(int n) { return rep("a", n) + "b"; } };
        Input digitsX = new Input() { public String of(int n) { return rep("1", n) + "x"; } };
        Input quoted = new Input() { public String of(int n) { return "\"" + rep("a", n) + "!"; } };

        // 一、教科书里的嵌套量词
        series("nested.ok", "(a+)+b", ns, aB, false);
        series("nested.fail", "(a+)+b", ns, aBang, false);
        series("word_list.fail", "^(\\w+\\s?)+$", ns, aBang, false);
        series("csv_digits.fail", "^(\\d+,?)+$", ns, digitsX, false);
        // 二、同样的结构加上反向引用、懒惰的外层量词、再多一层嵌套或有界重复
        series("backref.fail", "([\"'])(\\w+\\s?)+\\1", ns, quoted, false);
        series("lazy_outer.fail", "(a+)+?b", ns, aBang, false);
        series("three_levels.fail", "((a+)+)+b", ns, aBang, false);
        series("bounded.fail", "(a{1,10}){1,10}b", ns, aBang, false);
        // 三、改写之后
        series("fixed.simple", "a+b", ns, aBang, false);
        series("fixed.possessive", "(a++)+b", ns, aBang, false);
        series("fixed.atomic", "(?>a+)+b", ns, aBang, false);
        series("fixed.word_list", "^\\w+(\\s\\w+)*$", ns, aBang, false);
        series("fixed.csv_digits", "^\\d+(,\\d+)*,?$", ns, digitsX, false);
        series("fixed.backref", "([\"'])\\w+(\\s\\w+)*\\s?\\1", ns, quoted, false);
        // 四、不是指数、但随长度平方增长：查找「结尾的空白」，输入是大量空格后跟一个非空白字符
        int[] big = {1000, 2000, 4000, 8000};
        series("trailing_ws.fail", "\\s+$", big, new Input() { public String of(int n) { return rep(" ", n) + "x"; } }, true);
        series("trailing_ws.ok", "\\s+$", big, new Input() { public String of(int n) { return "x" + rep(" ", n); } }, true);
        // 五、给匹配设预算：超过 100 万次 charAt 就中止
        Counting limited = new Counting(rep("a", 40) + "!", 1000000L);
        String outcome;
        try { outcome = "返回 " + Pattern.compile("((a+)+)+b").matcher(limited).matches(); }
        catch (IllegalStateException e) { outcome = "抛出 IllegalStateException（" + e.getMessage() + "），已调用 " + (limited.calls - 1) + " 次"; }
        System.out.println("budget\t((a+)+)+b 匹配 40 个 a 加 !，预算 1000000 次：" + outcome);
        // 六、限制输入长度
        System.out.println("length_limit\t((a+)+)+b 匹配 12 个 a 加 !：" + run(Pattern.compile("((a+)+)+b"), rep("a", 12) + "!", false));
    }
}
