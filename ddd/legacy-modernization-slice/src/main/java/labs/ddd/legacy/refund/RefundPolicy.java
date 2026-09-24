package labs.ddd.legacy.refund;

import java.time.Duration;
import java.time.Instant;

/**
 * 新模型里的退款规则：按「距开场还有多久」分档，金额按分计算。
 * Rounding 决定是否保留旧系统「抹去不足一元的部分」的行为。
 */
public record RefundPolicy(
        Duration fullRefundBefore,
        Duration halfRefundBefore,
        Rounding rounding,
        HourBoundary boundary) {

    public enum Rounding { CENTS, DOWN_TO_YUAN }

    /** 旧系统把剩余时间截断成整小时再比较；新写法直接比较时长。 */
    public enum HourBoundary { EXACT, TRUNCATE_TO_HOURS }

    public static RefundPolicy natural() {
        return new RefundPolicy(Duration.ofHours(72), Duration.ofHours(24), Rounding.CENTS, HourBoundary.EXACT);
    }

    public static RefundPolicy legacyCompatible() {
        return new RefundPolicy(Duration.ofHours(72), Duration.ofHours(24), Rounding.DOWN_TO_YUAN, HourBoundary.TRUNCATE_TO_HOURS);
    }

    public Money refund(Money fee, Instant sessionStart, Instant now) {
        Duration left = Duration.between(now, sessionStart);
        if (boundary == HourBoundary.TRUNCATE_TO_HOURS) {
            left = Duration.ofHours(left.toHours());
        }
        long percent;
        if (boundary == HourBoundary.TRUNCATE_TO_HOURS ? left.compareTo(fullRefundBefore) > 0 : left.compareTo(fullRefundBefore) >= 0) {
            percent = 100;
        } else if (left.compareTo(halfRefundBefore) >= 0) {
            percent = 50;
        } else {
            percent = 0;
        }
        long cents = fee.cents() * percent / 100;
        return new Money(rounding == Rounding.DOWN_TO_YUAN ? cents / 100 * 100 : cents);
    }
}
