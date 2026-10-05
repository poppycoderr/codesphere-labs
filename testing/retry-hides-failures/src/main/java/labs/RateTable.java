package labs;

import java.util.Map;

/**
 * 汇率表：第一次查询时才加载。缺陷在 rate()：发现还没加载时触发加载，却没有等它完成就读了旧值，
 * 所以进程启动后的第一次查询返回 0，之后都正确。
 */
public final class RateTable {
    private static volatile Map<String, Integer> table = Map.of();
    private static volatile boolean loaded;

    private RateTable() {
    }

    public static int rate(String currency) {
        Map<String, Integer> current = table;
        if (!loaded) {
            load();
        }
        return current.getOrDefault(currency, 0);
    }

    private static void load() {
        table = Map.of("USD", 712, "EUR", 835);
        loaded = true;
    }
}
