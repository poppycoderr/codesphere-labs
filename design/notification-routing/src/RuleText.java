import java.util.*;
import java.util.function.Predicate;
import java.util.regex.*;

// 变化 3：把路由规则变成值班负责人可以维护的文本
//   route 支付高优先级: team=payment severity<=P2 -> phone:oncall-payment, chat:payment-alerts
final class RuleText {
    private static final Pattern LINE = Pattern.compile("route\\s+(\\S+):\\s*(.*?)\\s*->\\s*(.+)");
    private static final Pattern COND = Pattern.compile("(team|service)=(\\S+)|severity(<=|=)(P[123])");

    static List<Route> parse(String text, Set<String> knownChannels) {
        List<Route> routes = new ArrayList<>(); List<String> errors = new ArrayList<>();
        int no = 0;
        for (String raw : text.split("\n")) {
            no++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) { errors.add("第 " + no + " 行无法识别：" + line); continue; }
            Predicate<Alert> when = a -> true;
            for (String c : m.group(2).split("\\s+")) {
                if (c.isEmpty()) continue;
                Matcher cm = COND.matcher(c);
                if (!cm.matches()) { errors.add("第 " + no + " 行条件无法识别：" + c); continue; }
                when = when.and(condition(cm));
            }
            List<Target> to = new ArrayList<>();
            for (String t : m.group(3).split("\\s*,\\s*")) {
                String[] kv = t.split(":", 2);
                if (kv.length != 2 || !knownChannels.contains(kv[0])) { errors.add("第 " + no + " 行渠道不存在：" + t); continue; }
                to.add(new Target(kv[0], kv[1]));
            }
            routes.add(new Route(m.group(1), when, to));
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        return routes;
    }

    private static Predicate<Alert> condition(Matcher cm) {
        if (cm.group(1) != null) {
            String field = cm.group(1), value = cm.group(2);
            return field.equals("team") ? a -> a.team().equals(value) : a -> a.service().equals(value);
        }
        Severity s = Severity.valueOf(cm.group(4));
        return cm.group(3).equals("<=") ? a -> a.severity().compareTo(s) <= 0 : a -> a.severity() == s;
    }
}
