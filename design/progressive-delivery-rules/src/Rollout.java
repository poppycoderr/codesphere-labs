import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 渐进发布规则：确定性分桶、规则优先级与冲突检测、配置校验与版本回滚、多节点配置传播期间的结果跳变。
 * 运行：java Rollout，每行输出一个可断言的结果。
 */
public class Rollout {

    record User(String id, String tenant, String country) {
    }

    /** 一条规则：条件满足时给出结果。percentage 为 null 表示不按比例。 */
    record Rule(String id, int priority, Set<String> tenants, Set<String> countries, Integer percentage, boolean enabled) {
        boolean matches(String flag, User u) {
            if (tenants != null && !tenants.contains(u.tenant())) return false;
            if (countries != null && !countries.contains(u.country())) return false;
            return percentage == null || bucket(flag, u.id()) < percentage * 100;
        }
    }

    record Config(String flag, int version, List<Rule> rules) {
    }

    record Decision(boolean enabled, String ruleId, int version) {
    }

    /** 以「开关名:用户」求 SHA-256，取前 4 字节对 10000 取模，得到 0—9999 的桶。 */
    static int bucket(String flag, String userId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((flag + ":" + userId).getBytes(StandardCharsets.UTF_8));
            int v = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
            return Integer.remainderUnsigned(v, 10000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按优先级从高到低，第一条匹配的规则决定结果；都不匹配时关闭。 */
    static Decision evaluate(Config c, User u) {
        for (Rule r : c.rules()) {
            if (r.matches(c.flag(), u)) return new Decision(r.enabled(), r.id(), c.version());
        }
        return new Decision(false, "default", c.version());
    }

    /** 加载配置：校验字段、检测同优先级且条件可能重叠的规则，按优先级排序。失败时抛出异常，调用方保留旧版本。 */
    static Config load(String flag, int version, List<Rule> rules) {
        for (Rule r : rules) {
            if (r.percentage() != null && (r.percentage() < 0 || r.percentage() > 100)) {
                throw new IllegalArgumentException("规则 " + r.id() + " 的比例 " + r.percentage() + " 不在 0—100 之间");
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                Rule a = rules.get(i);
                Rule b = rules.get(j);
                if (a.priority() == b.priority() && overlap(a.tenants(), b.tenants()) && overlap(a.countries(), b.countries())
                        && a.enabled() != b.enabled()) {
                    throw new IllegalArgumentException("规则 " + a.id() + " 与 " + b.id() + " 优先级相同、条件重叠、结果相反");
                }
            }
        }
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(Rule::priority).reversed());
        return new Config(flag, version, List.copyOf(sorted));
    }

    static boolean overlap(Set<String> a, Set<String> b) {
        if (a == null || b == null) return true;
        for (String x : a) if (b.contains(x)) return true;
        return false;
    }

    static List<User> users(int n) {
        List<User> us = new ArrayList<>();
        String[] tenants = {"acme", "globex", "initech", "umbrella"};
        String[] countries = {"CN", "SG", "DE"};
        for (int i = 0; i < n; i++) us.add(new User("u" + i, tenants[i % 4], countries[i % 3]));
        return us;
    }

    static void out(String key, Object value) {
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) {
        bucketing();
        priorities();
        validationAndRollback();
        propagation();
    }

    static Config percent(String flag, int version, int p) {
        return load(flag, version, List.of(new Rule("ramp", 1, null, null, p, true)));
    }

    static void bucketing() {
        List<User> us = users(100_000);
        Config p10 = percent("new-checkout", 1, 10);
        Config p20 = percent("new-checkout", 2, 20);
        Set<String> on10 = new HashSet<>();
        Set<String> on20 = new HashSet<>();
        for (User u : us) {
            if (evaluate(p10, u).enabled()) on10.add(u.id());
            if (evaluate(p20, u).enabled()) on20.add(u.id());
        }
        out("bucket.share", "10 万用户：10% 规则命中 " + on10.size() + "，20% 规则命中 " + on20.size());
        out("bucket.monotonic", "10% 命中的用户在 20% 时仍命中：" + on20.containsAll(on10));
        int stable = 0;
        for (User u : us) if (evaluate(percent("new-checkout", 1, 10), u).enabled() == on10.contains(u.id())) stable++;
        out("bucket.stable", "重新加载同一配置后结果一致的用户 " + stable + " / " + us.size());

        Random random = new Random(42);
        int flipped = 0;
        for (int i = 0; i < 10_000; i++) {
            boolean first = random.nextInt(100) < 10;
            boolean second = random.nextInt(100) < 10;
            if (first != second) flipped++;
        }
        out("bucket.random", "按随机数决定：1 万个用户各请求两次，两次结果不同 " + flipped + " 人");

        Set<String> onA = new HashSet<>();
        Set<String> onB = new HashSet<>();
        Set<String> onAUnsalted = new HashSet<>();
        Set<String> onBUnsalted = new HashSet<>();
        for (User u : us) {
            if (bucket("new-checkout", u.id()) < 1000) onA.add(u.id());
            if (bucket("dark-mode", u.id()) < 1000) onB.add(u.id());
            if (bucket("", u.id()) < 1000) {
                onAUnsalted.add(u.id());
                onBUnsalted.add(u.id());
            }
        }
        Set<String> both = new HashSet<>(onA);
        both.retainAll(onB);
        out("bucket.salted", "两个 10% 的开关，以开关名加盐：同时命中 " + both.size() + " 人；不加盐：同时命中 " + onAUnsalted.size() + " 人");
    }

    static void priorities() {
        Config c = load("new-checkout", 3, List.of(
                new Rule("ramp-10", 10, null, null, 10, true),
                new Rule("deny-umbrella", 100, Set.of("umbrella"), null, null, false),
                new Rule("allow-acme", 50, Set.of("acme"), null, null, true),
                new Rule("de-first", 50, null, Set.of("DE"), 50, true)));
        User acmeDe = new User("u7", "acme", "DE");
        User umbrella = new User("u9", "umbrella", "DE");
        out("rule.priority", "acme/DE 用户 → " + evaluate(c, acmeDe) + "；umbrella 用户 → " + evaluate(c, umbrella));
        try {
            load("new-checkout", 4, List.of(new Rule("allow-acme", 50, Set.of("acme"), null, null, true),
                    new Rule("deny-de", 50, null, Set.of("DE"), null, false)));
        } catch (IllegalArgumentException e) {
            out("rule.conflict", e.getMessage());
        }
    }

    static void validationAndRollback() {
        Config current = percent("new-checkout", 1, 10);
        try {
            current = load("new-checkout", 2, List.of(new Rule("ramp", 1, null, null, 120, true)));
        } catch (IllegalArgumentException e) {
            out("config.rejected", e.getMessage() + "；继续使用版本 " + current.version());
        }
        List<User> us = users(100_000);
        Config v1 = current;
        Config v2 = percent("new-checkout", 2, 50);
        Config rollback = load("new-checkout", 3, v1.rules());
        int same = 0;
        int changedUnderV2 = 0;
        for (User u : us) {
            boolean a = evaluate(v1, u).enabled();
            if (a != evaluate(v2, u).enabled()) changedUnderV2++;
            if (a == evaluate(rollback, u).enabled()) same++;
        }
        out("config.rollback", "版本 2（50%）改变了 " + changedUnderV2 + " 个用户的结果；以版本 1 的规则发布版本 3 回滚后，与版本 1 一致的用户 " + same + " / " + us.size());
    }

    /** 两个节点：A 在第 0 秒拿到新配置（10% → 50%），B 晚 30 秒。每个用户每 10 秒请求一次，轮流打到 A、B。 */
    static void propagation() {
        List<User> us = users(10_000);
        Config old = percent("new-checkout", 1, 10);
        Config neu = percent("new-checkout", 2, 50);
        int flipping = 0;
        int flips = 0;
        for (User u : us) {
            Boolean prev = null;
            int f = 0;
            for (int t = 0; t < 60; t += 10) {
                boolean nodeA = (t / 10) % 2 == 0;
                Config c = nodeA ? neu : (t >= 30 ? neu : old);
                boolean v = evaluate(c, u).enabled();
                if (prev != null && prev != v) f++;
                prev = v;
            }
            if (f > 1) flipping++;
            flips += f;
        }
        out("propagation.flip", "1 万个用户在 60 秒内各请求 6 次：结果来回变化（超过一次）的用户 " + flipping + " 人，变化 " + flips + " 次");
    }
}
