import java.time.*;
import java.util.*;

// 旧系统：规则、格式解析和发送混在一起（这里只保留它算出的通知目标）
final class LegacyAlertService {
    List<Target> handle(Map<String, String> p) {
        List<Target> out = new ArrayList<>();
        String team = p.get("team"), sev = p.get("severity");
        int hour = Instant.parse(p.get("at")).atZone(ZoneId.of("Asia/Shanghai")).getHour();
        if ("payment".equalsIgnoreCase(team)) {
            if (sev.equals("P1") || sev.equals("critical")) {
                out.add(new Target("phone", "oncall-payment")); out.add(new Target("chat", "payment-alerts"));
                if (hour >= 22 || hour < 8) out.add(new Target("email", "payment-lead"));
            } else if (sev.equals("P2")) {
                out.add(new Target("phone", "oncall-payment")); out.add(new Target("chat", "payment-alerts"));
            } else out.add(new Target("chat", "payment-alerts"));
        } else if ("search".equals(team)) {
            out.add(new Target("chat", "search-alerts"));
        }
        return out;
    }
}

// 防腐层：把旧格式翻译成新模型；翻译规则的变化不进入 Router
final class AlertTranslator {
    private final boolean legacyCompat;
    AlertTranslator(boolean legacyCompat) { this.legacyCompat = legacyCompat; }
    Optional<Alert> translate(Map<String, String> p) {
        String team = p.get("team"), sev = p.get("severity");
        if (legacyCompat) { team = team.toLowerCase(Locale.ROOT); if (sev.equals("critical")) sev = "P1"; }
        Severity s;
        try { s = Severity.valueOf(sev); } catch (IllegalArgumentException e) { return Optional.empty(); }
        return Optional.of(new Alert(p.get("service"), team, s, p.get("fp"), Instant.parse(p.get("at"))));
    }
}
