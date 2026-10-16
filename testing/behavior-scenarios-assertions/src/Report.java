import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 读取 surefire 的 XML 报告，按「类 / 测试名」排序输出每个测试的结果与失败信息的前两行 */
public class Report {
    public static void main(String[] args) throws Exception {
        List<String> rows = new ArrayList<>();
        Pattern tc = Pattern.compile("<testcase name=\"(.*?)\" classname=\"(.*?)\"[^>]*?(/>|>(.*?)</testcase>)", Pattern.DOTALL);
        Pattern fail = Pattern.compile("<(failure|error)(?: message=\"(.*?)\")?[^>]*?(?:/>|>)", Pattern.DOTALL);
        try (Stream<Path> s = Files.list(Path.of("target/surefire-reports"))) {
            for (Path p : s.filter(x -> x.getFileName().toString().startsWith("TEST-")).sorted().toList()) {
                Matcher m = tc.matcher(Files.readString(p));
                while (m.find()) {
                    String body = m.group(4) == null ? "" : m.group(4);
                    String cls = m.group(2).substring(m.group(2).lastIndexOf('.') + 1);
                    Matcher f = fail.matcher(body);
                    String result = "通过";
                    if (body.contains("<skipped")) result = "跳过";
                    else if (f.find()) {
                        String msg = f.group(2) == null ? "" : unescape(f.group(2));
                        List<String> lines = msg.lines().map(String::strip).filter(l -> !l.isEmpty()).limit(Integer.parseInt(args.length > 0 ? args[0] : "2")).toList();
                        result = (f.group(1).equals("failure") ? "失败：" : "错误：") + String.join(" ⏎ ", lines);
                    }
                    rows.add(cls + "." + unescape(m.group(1)) + "\t" + result);
                }
            }
        }
        rows.stream().sorted().forEach(System.out::println);
    }
    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&");
    }
}
