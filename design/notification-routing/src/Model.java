import java.time.*;
import java.util.*;
import java.util.function.Predicate;

enum Severity { P1, P2, P3 }

record Alert(String service, String team, Severity severity, String fingerprint, Instant at) {}

record Target(String channel, String address) {}

record Route(String name, Predicate<Alert> when, List<Target> to) {
    Route { to = List.copyOf(to); }
}

// 第一版的全部核心：给定告警，算出要通知谁。不发送、不读配置、不看时间。
final class Router {
    private final List<Route> routes;
    Router(List<Route> routes) { this.routes = List.copyOf(routes); }
    List<Target> route(Alert a) {
        LinkedHashSet<Target> out = new LinkedHashSet<>();
        for (Route r : routes) if (r.when().test(a)) out.addAll(r.to());
        return List.copyOf(out);
    }
}

// 变化 1：渠道
interface Channel { String name(); void send(Target t, Alert a); }

// 变化 2：抑制策略
interface Suppression { boolean suppress(Alert a, Target t, Instant now); }

final class Dedup implements Suppression {
    private final Duration window;
    private final Map<String, Instant> last = new HashMap<>();
    Dedup(Duration window) { this.window = window; }
    public boolean suppress(Alert a, Target t, Instant now) {
        String key = a.fingerprint() + "|" + t;
        Instant prev = last.get(key);
        if (prev != null && Duration.between(prev, now).compareTo(window) < 0) return true;
        last.put(key, now);
        return false;
    }
}

final class QuietHours implements Suppression {
    private final ZoneId zone; private final LocalTime from, to; private final Severity atMost;
    QuietHours(ZoneId zone, LocalTime from, LocalTime to, Severity atMost) { this.zone = zone; this.from = from; this.to = to; this.atMost = atMost; }
    public boolean suppress(Alert a, Target t, Instant now) {
        if (a.severity().compareTo(atMost) < 0) return false;          // 比 atMost 更严重的不静默
        LocalTime local = now.atZone(zone).toLocalTime();
        return !local.isBefore(from) || local.isBefore(to);           // 跨午夜区间
    }
}

final class Dispatcher {
    private final Router router; private final Map<String, Channel> channels; private final List<Suppression> rules; private final Clock clock;
    Dispatcher(Router router, List<Channel> channels, List<Suppression> rules, Clock clock) {
        this.router = router; this.rules = List.copyOf(rules); this.clock = clock;
        Map<String, Channel> m = new HashMap<>();
        for (Channel c : channels) m.put(c.name(), c);
        this.channels = Map.copyOf(m);
    }
    List<Target> dispatch(Alert a) {
        Instant now = clock.instant();
        List<Target> sent = new ArrayList<>();
        for (Target t : router.route(a)) {
            if (rules.stream().anyMatch(r -> r.suppress(a, t, now))) continue;
            Channel c = channels.get(t.channel());
            if (c == null) throw new IllegalStateException("未注册的渠道：" + t.channel());
            c.send(t, a);
            sent.add(t);
        }
        return sent;
    }
}
