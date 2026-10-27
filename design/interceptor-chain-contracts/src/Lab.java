import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * 拦截链的三条契约：任一检查失败都要停止（包括检查自身出错与依赖不可用）；只释放自己拿到的许可；
 * 链与候选列表在请求之间不共享可变状态。另含「首个匹配」规则的遮蔽。
 */
public class Lab {

    record Request(String path, Map<String, String> headers) {}

    static final class Denied extends RuntimeException {
        Denied(String m) {
            super(m);
        }
    }

    interface Check {
        void check(Request r);
    }

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    // ---------- 1. 失败放行 ----------

    static final Check AUTH = r -> {
        String token = r.headers().get("Authorization");
        if (!token.startsWith("Bearer ")) { // 缺少头时这里抛 NullPointerException
            throw new Denied("令牌格式不对");
        }
    };

    static final Check BLOCKLIST = r -> {
        String user = r.headers().get("X-User"); // 取不到用户时为 null
        if (new ArrayList<>(List.of("mallory")).contains(user)) {
            throw new Denied("用户在黑名单");
        }
    };

    static final Check BLOCKLIST_STRICT = r -> {
        String user = r.headers().get("X-User");
        if (user == null) {
            throw new Denied("缺少用户身份");
        }
        if (user.equals("mallory")) {
            throw new Denied("用户在黑名单");
        }
    };

    static final Check MODERATION = r -> {
        if (r.headers().containsKey("X-Moderation-Down")) {
            throw new IllegalStateException("审核服务超时");
        }
    };

    /** 朴素链：只把 Denied 当拒绝，其他异常记一笔后继续。 */
    static String naiveChain(Request r, List<Check> checks) {
        for (Check c : checks) {
            try {
                c.check(r);
            } catch (Denied d) {
                return "拒绝：" + d.getMessage();
            } catch (RuntimeException e) {
                // 「检查器自己出错，不能影响业务」
            }
        }
        return "执行业务";
    }

    /** 失败即停止：任何异常都终止链；放行必须是检查明确得出的结论。 */
    static String closedChain(Request r, List<Check> checks) {
        for (Check c : checks) {
            try {
                c.check(r);
            } catch (Denied d) {
                return "拒绝：" + d.getMessage();
            } catch (RuntimeException e) {
                return "拒绝：检查无法完成（" + e.getClass().getSimpleName() + "）";
            }
        }
        return "执行业务";
    }

    static void failOpen() {
        List<Check> checks = List.of(AUTH, MODERATION);
        Request noToken = new Request("/orders", Map.of());
        out("open.missing-header", "没有 Authorization 头，认证检查抛 NullPointerException：朴素链 → " + naiveChain(noToken, checks)
                + "；失败即停止 → " + closedChain(noToken, checks));
        Request down = new Request("/orders", Map.of("Authorization", "Bearer t", "X-Moderation-Down", "1"));
        out("open.dependency", "审核服务超时：朴素链 → " + naiveChain(down, checks) + "；失败即停止 → " + closedChain(down, checks));
        Request noUser = new Request("/orders", Map.of("Authorization", "Bearer t"));
        out("open.null-key", "黑名单按 X-User 判断，请求里没有这个头：取到 null，「不在黑名单里」→ " + closedChain(noUser, List.of(AUTH, BLOCKLIST))
                + "；缺少身份时直接拒绝 → " + closedChain(noUser, List.of(AUTH, BLOCKLIST_STRICT)));
    }

    // ---------- 2. 许可：只释放自己拿到的 ----------

    static void permits() {
        Semaphore naive = new Semaphore(2);
        naive.acquireUninterruptibly(2); // 两个慢请求正在占用
        int admitted = 0;
        for (int i = 0; i < 3; i++) {
            boolean got = naive.tryAcquire();
            if (got) {
                admitted++;
            }
            try {
                // 拿到就处理，拿不到就返回「繁忙」
            } finally {
                naive.release(); // 不管拿没拿到都释放
            }
        }
        naive.release(2); // 两个慢请求结束
        out("permit.naive", "上限 2，两个请求占用期间又来 3 个，finally 无条件 release：本该全部被拒的 3 个里放进了 " + admitted
                + " 个，全部结束后可用许可 " + naive.availablePermits() + "（上限从 2 变成了 3）");

        Semaphore fixed = new Semaphore(2);
        fixed.acquireUninterruptibly(2);
        int admitted2 = 0;
        for (int i = 0; i < 3; i++) {
            if (!fixed.tryAcquire()) {
                continue;
            }
            admitted2++;
            try {
                // 处理
            } finally {
                fixed.release();
            }
        }
        fixed.release(2);
        out("permit.owned", "只在拿到许可后进入 try、在 finally 里释放：放进 " + admitted2 + " 个，全部结束后可用许可 " + fixed.availablePermits());
    }

    // ---------- 3. 共享的候选列表 ----------

    static void sharedCandidates() {
        List<String> shared = new ArrayList<>(List.of("node-a", "node-b", "node-c"));
        Function<List<String>, List<String>> dropUnhealthy = list -> {
            for (String n : list) {
                if (n.equals("node-b") || n.equals("node-c")) { // b、c 都不健康
                    list.remove(n);
                }
            }
            return list;
        };
        try {
            dropUnhealthy.apply(shared);
            out("candidates.foreach", "node-b、node-c 都不健康，在 for-each 里删除：没有报错，结果 " + shared + "（删掉倒数第二个元素后迭代提前结束，node-c 没被检查）");
        } catch (ConcurrentModificationException e) {
            out("candidates.foreach", "在 for-each 里删除：ConcurrentModificationException");
        }

        List<String> registry = new ArrayList<>(List.of("node-a", "node-b", "node-c"));
        Function<List<String>, List<String>> dropInPlace = list -> {
            list.removeIf(n -> n.equals("node-b"));
            return list;
        };
        dropInPlace.apply(registry); // 第一个请求：node-b 对它暂时不可用
        out("candidates.shared", "请求一的过滤器直接改注册表里的列表，请求二看到的候选：" + registry);

        List<String> registry2 = List.of("node-a", "node-b", "node-c");
        List<String> forRequest1 = new ArrayList<>(registry2);
        forRequest1.removeIf(n -> n.equals("node-b"));
        out("candidates.copy", "每个请求复制一份再过滤：请求一 " + forRequest1 + "，请求二 " + registry2);
    }

    // ---------- 4. 首个匹配的规则遮蔽 ----------

    record Rule(String pattern, String decision) {
        boolean matches(String path) {
            return pattern.endsWith("/**") ? path.startsWith(pattern.substring(0, pattern.length() - 2)) : path.equals(pattern);
        }
    }

    static String firstMatch(List<Rule> rules, String path) {
        for (Rule r : rules) {
            if (r.matches(path)) {
                return r.decision() + "（命中 " + r.pattern() + "）";
            }
        }
        return "拒绝（没有规则命中）";
    }

    static void ordering() {
        List<Rule> wideFirst = List.of(new Rule("/api/**", "放行"), new Rule("/api/admin/**", "拒绝"));
        out("rules.wide-first", "规则按书写顺序首个匹配，宽规则在前：/api/admin/users → " + firstMatch(wideFirst, "/api/admin/users"));
        List<Rule> specificFirst = new ArrayList<>(wideFirst);
        specificFirst.sort((a, b) -> Integer.compare(b.pattern().length(), a.pattern().length()));
        out("rules.specific-first", "按前缀长度从具体到宽泛排序：/api/admin/users → " + firstMatch(specificFirst, "/api/admin/users")
                + "，/api/orders → " + firstMatch(specificFirst, "/api/orders") + "，/health → " + firstMatch(specificFirst, "/health"));
    }

    public static void main(String[] args) {
        failOpen();
        permits();
        sharedCandidates();
        ordering();
    }
}
