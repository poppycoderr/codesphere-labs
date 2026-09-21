import java.time.*;
import java.util.*;

// 影子比对：新旧两套逻辑处理同一批历史告警，按差异原因分类计数
public class Shadow {
    static final String RULES = """
        route 支付高优先级: team=payment severity<=P2 -> phone:oncall-payment, chat:payment-alerts
        route 支付全部: team=payment -> chat:payment-alerts
        route 搜索: team=search -> chat:search-alerts
        """;

    public static void main(String[] args) {
        List<Map<String, String>> history = replay(10_000, 42);
        run("第一次影子比对（严格翻译）", history, new AlertTranslator(false), Set.of());
        run("第二次（翻译层兼容大小写与 critical）", history, new AlertTranslator(true), Set.of());
        run("第三次（夜间邮件确认下线，登记为有意差异）", history, new AlertTranslator(true), Set.of("email"));
    }

    static void run(String title, List<Map<String, String>> history, AlertTranslator tr, Set<String> intended) {
        LegacyAlertService legacy = new LegacyAlertService();
        Router router = new Router(RuleText.parse(RULES, Set.of("phone", "chat")));
        Map<String, Integer> diff = new TreeMap<>();
        int same = 0;
        for (Map<String, String> p : history) {
            List<Target> old = legacy.handle(p);
            Optional<Alert> a = tr.translate(p);
            if (a.isEmpty()) { diff.merge("新系统无法识别 severity=" + p.get("severity"), 1, Integer::sum); continue; }
            List<Target> neu = router.route(a.get());
            Set<Target> o = new HashSet<>(old), n = new HashSet<>(neu);
            o.removeIf(t -> intended.contains(t.channel()));
            if (o.equals(n)) { same++; continue; }
            Set<Target> onlyOld = new HashSet<>(o); onlyOld.removeAll(n);
            Set<Target> onlyNew = new HashSet<>(n); onlyNew.removeAll(o);
            String key = (onlyOld.isEmpty() ? "" : "旧系统多发 " + onlyOld.stream().map(t -> t.channel()).sorted().toList())
                       + (onlyNew.isEmpty() ? "" : " 新系统多发 " + onlyNew.stream().map(t -> t.channel()).sorted().toList())
                       + "（team=" + p.get("team") + "）";
            diff.merge(key, 1, Integer::sum);
        }
        System.out.println("== " + title + "：一致 " + same + " / " + history.size());
        diff.forEach((k, v) -> System.out.println("   " + v + "  " + k));
    }

    // 合成的历史告警：团队名偶尔大小写不一致，少量旧格式 severity，时间均匀分布在一周内
    static List<Map<String, String>> replay(int n, long seed) {
        Random r = new Random(seed);
        String[] teams = {"payment", "payment", "payment", "search", "search", "growth"};
        String[] sevs = {"P1", "P2", "P3", "P3", "P3", "P3"};
        List<Map<String, String>> out = new ArrayList<>();
        Instant base = Instant.parse("2026-09-14T00:00:00Z");
        for (int i = 0; i < n; i++) {
            String team = teams[r.nextInt(teams.length)];
            if (team.equals("payment") && r.nextInt(100) < 2) team = "Payment";
            String sev = r.nextInt(100) < 3 ? "critical" : sevs[r.nextInt(sevs.length)];
            Instant at = base.plusSeconds(r.nextInt(7 * 24 * 3600));
            out.add(Map.of("service", team + "-api", "team", team, "severity", sev, "fp", "fp-" + r.nextInt(500), "at", at.toString()));
        }
        return out;
    }
}
