import java.util.*;
import java.util.function.Predicate;
import static java.util.List.of;

public final class Dsl {
    static Predicate<Alert> team(String t) { return a -> a.team().equals(t); }
    static Predicate<Alert> atLeast(Severity s) { return a -> a.severity().compareTo(s) <= 0; }
    static Target phone(String who) { return new Target("phone", who); }
    static Target chat(String room) { return new Target("chat", room); }
    static RouteSpec when(Predicate<Alert> p) { return new RouteSpec(p); }
    record RouteSpec(Predicate<Alert> when) {
        RouteSpec and(Predicate<Alert> more) { return new RouteSpec(when.and(more)); }
        Route notify(String name, Target... to) { return new Route(name, when, List.of(to)); }
    }
    public static void main(String[] a) {
        Router r = new Router(of(
            when(team("payment")).and(atLeast(Severity.P2)).notify("支付高优先级", phone("oncall-payment"), chat("payment-alerts")),
            when(team("payment")).notify("支付全部", chat("payment-alerts"))));
        System.out.println(r.route(new Alert("pay-api", "payment", Severity.P1, "fp", java.time.Instant.EPOCH)));
        System.out.println(r.route(new Alert("pay-api", "payment", Severity.P3, "fp", java.time.Instant.EPOCH)));
    }
}
