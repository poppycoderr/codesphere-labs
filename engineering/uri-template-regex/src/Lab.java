import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.glassfish.jersey.uri.UriTemplate;

/** URI 模板编译成正则：字面字符转义、变量里的捕获组让下标错位、替换串里的 $ 与 \、候选模板的优先级与比较器契约 */
public class Lab {
    static final Pattern VAR = Pattern.compile("\\{(\\w+)(?:\\s*:\\s*([^}]+))?}");

    /** 朴素编译：字面部分原样拼进正则，变量按出现顺序编号 */
    static Pattern naive(String template, List<String> names) {
        StringBuilder re = new StringBuilder(); Matcher m = VAR.matcher(template); int last = 0;
        while (m.find()) {
            re.append(template, last, m.start());
            names.add(m.group(1));
            re.append("(").append(m.group(2) == null ? "[^/]+?" : m.group(2)).append(")");
            last = m.end();
        }
        return Pattern.compile(re.append(template.substring(last)).toString());
    }
    /** 改正：字面部分用 Pattern.quote，变量用命名组，不受变量正则里的捕获组影响 */
    static Pattern quoted(String template) {
        StringBuilder re = new StringBuilder(); Matcher m = VAR.matcher(template); int last = 0;
        while (m.find()) {
            if (m.start() > last) re.append(Pattern.quote(template.substring(last, m.start())));
            re.append("(?<").append(m.group(1)).append(">").append(m.group(2) == null ? "[^/]+?" : m.group(2)).append(")");
            last = m.end();
        }
        if (last < template.length()) re.append(Pattern.quote(template.substring(last)));
        return Pattern.compile(re.toString());
    }
    static String naiveValues(String template, String path) {
        List<String> names = new ArrayList<>(); Matcher m = naive(template, names).matcher(path);
        if (!m.matches()) return "不匹配";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < names.size(); i++) s.append(names.get(i)).append("=").append(m.group(i + 1)).append(" ");
        return s.toString().trim();
    }
    static String quotedValues(String template, String path) {
        Matcher m = quoted(template).matcher(path);
        if (!m.matches()) return "不匹配";
        StringBuilder s = new StringBuilder(); Matcher v = VAR.matcher(template);
        while (v.find()) s.append(v.group(1)).append("=").append(m.group(v.group(1))).append(" ");
        return s.toString().trim();
    }
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    public static void main(String[] args) {
        out("env", "java=" + Runtime.version() + " jersey=" + org.glassfish.jersey.internal.Version.getVersion());
        String t1 = "/files/{name}.txt";
        out("literal.naive", t1 + " 朴素编译后匹配 /files/reportXtxt：" + naiveValues(t1, "/files/reportXtxt"));
        out("literal.quoted", "字面部分 Pattern.quote 后匹配 /files/reportXtxt：" + quotedValues(t1, "/files/reportXtxt") + "；匹配 /files/report.txt：" + quotedValues(t1, "/files/report.txt"));
        out("literal.jersey", "Jersey UriTemplate 匹配 /files/reportXtxt：" + jersey(t1, "/files/reportXtxt") + "；匹配 /files/report.txt：" + jersey(t1, "/files/report.txt"));

        String t2 = "/orders/{id: (a|b)\\d+}/{part}";
        out("groups.naive", t2 + " 朴素按出现顺序取组，匹配 /orders/a12/items：" + naiveValues(t2, "/orders/a12/items"));
        out("groups.named", "改用命名组：" + quotedValues(t2, "/orders/a12/items"));
        out("groups.jersey", "Jersey UriTemplate：" + jersey(t2, "/orders/a12/items"));

        String r1; try { r1 = "price={v}".replaceAll("\\{v}", "US$5"); } catch (RuntimeException e) { r1 = e.getClass().getSimpleName() + "：" + e.getMessage(); }
        out("replace.dollar", "用 replaceAll 把 {v} 换成 US$5：" + r1);
        out("replace.quoted", "替换串先 Matcher.quoteReplacement：" + "price={v}".replaceAll("\\{v}", Matcher.quoteReplacement("US$5")));
        String oneBackslash = "C:\\tmp";
        out("replace.backslash", "替换值是 C:\\tmp（字符串里一个反斜杠）：replaceAll 得到「" + "path={v}".replaceAll("\\{v}", oneBackslash) + "」，quoteReplacement 后得到「" + "path={v}".replaceAll("\\{v}", Matcher.quoteReplacement(oneBackslash)) + "」");

        List<String> templates = List.of("/users/{id}", "/users/me", "/users/{id: \\d+}", "/users/{id}/{tab}", "/{any: .*}");
        List<UriTemplate> sorted = new ArrayList<>(templates.stream().map(UriTemplate::new).toList());
        sorted.sort(UriTemplate.COMPARATOR);
        out("order.jersey", "按 Jersey UriTemplate.COMPARATOR 排序：" + sorted.stream().map(UriTemplate::getTemplate).toList());
        for (String path : List.of("/users/me", "/users/42", "/users/abc", "/users/42/posts", "/static/a.css")) {
            String winner = sorted.stream().filter(t -> t.match(path, new HashMap<>())).map(UriTemplate::getTemplate).findFirst().orElse("无");
            out("match." + path.replace("/", "_"), path + " 由 " + winner + " 处理");
        }
        List<String> byLength = new ArrayList<>(templates); byLength.sort(Comparator.comparingInt(String::length).reversed());
        String naiveWinner = byLength.stream().filter(t -> quoted(t).matcher("/users/me").matches()).findFirst().orElse("无");
        out("order.length", "按模板字符串长度降序排序：" + byLength + "；/users/me 由 " + naiveWinner + " 处理");

        Comparator<String> neverEqual = (a, b) -> a.length() >= b.length() ? -1 : 1;
        int violations = 0, pairs = 0;
        for (String a : templates) for (String b : templates) { pairs++; if (Integer.signum(neverEqual.compare(a, b)) != -Integer.signum(neverEqual.compare(b, a))) violations++; }
        int jv = 0;
        for (UriTemplate a : sorted) for (UriTemplate b : sorted) if (Integer.signum(UriTemplate.COMPARATOR.compare(a, b)) != -Integer.signum(UriTemplate.COMPARATOR.compare(b, a))) jv++;
        out("contract", "检查 sgn(compare(a,b)) == -sgn(compare(b,a))：「长度大于等于就返回 -1」的比较器在 " + pairs + " 对里违反 " + violations + " 对（都是元素与自身比较：compare(a,a) 返回 -1 而不是 0），Jersey 的比较器违反 " + jv + " 对");
    }
    static String jersey(String template, String path) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!new UriTemplate(template).match(path, values)) return "不匹配";
        StringBuilder s = new StringBuilder(); values.forEach((k, v) -> s.append(k).append("=").append(v).append(" "));
        return s.toString().trim();
    }
}
