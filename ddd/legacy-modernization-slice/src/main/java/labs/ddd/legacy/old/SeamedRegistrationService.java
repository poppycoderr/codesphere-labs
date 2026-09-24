package labs.ddd.legacy.old;

import java.util.Map;

/** 与 LegacyRegistrationService 行为相同，只是把退款计算移到了 RefundCalculator 接缝后面。 */
public class SeamedRegistrationService extends LegacyRegistrationService {

    /** 原封不动搬出来的旧算法。 */
    public static final RefundCalculator ORIGINAL = (fee, start, now) -> {
        long hours = (start - now) / 3_600_000;
        int percent = hours > 72 ? 100 : hours >= 24 ? 50 : 0;
        return fee * percent / 100 / 100 * 100;
    };

    private final RefundCalculator calculator;

    public SeamedRegistrationService(RefundCalculator calculator) {
        this.calculator = calculator;
    }

    @Override
    public int cancel(String id, long nowMillis) {
        Map<String, Object> row = rows.get(id);
        if (row == null || (int) row.get("status") != CONFIRMED) {
            return 0;
        }
        row.put("status", CANCELLED);
        return calculator.refundCents((int) row.get("fee"), (long) row.get("start"), nowMillis);
    }
}
