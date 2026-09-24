package labs.ddd.legacy.old;

import java.util.HashMap;
import java.util.Map;

/**
 * 遗留的报名服务：一个类里有报名、取消和退款计算，状态用整数，行用 Map 表示。
 * 退款规则写在 cancel 里，没有文档；它的真实行为只能从代码和线上数据推断。
 */
public class LegacyRegistrationService {

    public static final int CONFIRMED = 1;
    public static final int WAITLIST = 2;
    public static final int CANCELLED = 3;

    protected final Map<String, Map<String, Object>> rows = new HashMap<>();

    public void register(String id, int feeCents, long sessionStartMillis) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", CONFIRMED);
        row.put("fee", feeCents);
        row.put("start", sessionStartMillis);
        rows.put(id, row);
    }

    /** 返回退款金额（分）。 */
    public int cancel(String id, long nowMillis) {
        Map<String, Object> row = rows.get(id);
        if (row == null || (int) row.get("status") != CONFIRMED) {
            return 0;
        }
        row.put("status", CANCELLED);
        int fee = (int) row.get("fee");
        long hours = ((long) row.get("start") - nowMillis) / 3_600_000;
        int percent;
        if (hours > 72) {
            percent = 100;
        } else if (hours >= 24) {
            percent = 50;
        } else {
            percent = 0;
        }
        int refund = fee * percent / 100;
        return refund / 100 * 100;
    }
}
