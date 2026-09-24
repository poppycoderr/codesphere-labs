package labs.ddd.legacy.refund;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import labs.ddd.legacy.old.RefundCalculator;

/**
 * 绞杀式切换的入口：按报名 id 的哈希把一部分流量交给新模型，其余走旧算法并在影子里比对。
 * percentToNew 可以随时调回 0，旧路径始终保留到切换完成。
 */
public final class RefundRouter {

    public record Mismatch(
            String registrationId,
            long legacyCents,
            long newCents) {
    }

    private final RefundCalculator legacy;
    private final RefundPolicy policy;
    private volatile int percentToNew;
    private final List<Mismatch> mismatches = new ArrayList<>();
    private int routedToNew;

    public RefundRouter(RefundCalculator legacy, RefundPolicy policy) {
        this.legacy = legacy;
        this.policy = policy;
    }

    public void route(int percent) {
        this.percentToNew = percent;
    }

    public synchronized long refund(String registrationId, int feeCents, long startMillis, long nowMillis) {
        long legacyCents = legacy.refundCents(feeCents, startMillis, nowMillis);
        long newCents = policy.refund(new Money(feeCents), Instant.ofEpochMilli(startMillis), Instant.ofEpochMilli(nowMillis)).cents();
        if (legacyCents != newCents) {
            mismatches.add(new Mismatch(registrationId, legacyCents, newCents));
        }
        if (Math.floorMod(registrationId.hashCode(), 100) < percentToNew) {
            routedToNew++;
            return newCents;
        }
        return legacyCents;
    }

    public synchronized List<Mismatch> mismatches() {
        return List.copyOf(mismatches);
    }

    public synchronized int routedToNew() {
        return routedToNew;
    }
}
