package labs;

import java.util.HashMap;
import java.util.Map;

/** 进程内共享的库存表，测试之间没有任何隔离。 */
public final class Inventory {
    private static final Map<String, Integer> STOCK = new HashMap<>();

    private Inventory() {
    }

    public static void add(String sku, int n) {
        STOCK.merge(sku, n, Integer::sum);
    }

    public static int count(String sku) {
        return STOCK.getOrDefault(sku, 0);
    }
}
