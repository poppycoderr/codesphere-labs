import java.util.EnumMap;
import java.util.Map;

/** 同一条运费规则的五种写法。规则：会员免运费；否则按地区定基础运费，订单满 99 减半，偏远地区超重另加 15。 */
public class Shipping {
    enum Region { LOCAL, PROVINCE, REMOTE }

    // 写法一：层层嵌套
    static int nested(Region r, int amount, boolean member, boolean heavy) {
        int fee;
        if (!member) {
            if (r == Region.LOCAL) {
                fee = 6;
            } else {
                if (r == Region.PROVINCE) {
                    fee = 10;
                } else {
                    if (heavy) {
                        fee = 20 + 15;
                    } else {
                        fee = 20;
                    }
                }
            }
            if (amount >= 99) {
                fee = fee / 2;
            }
        } else {
            fee = 0;
        }
        return fee;
    }

    // 写法二：卫语句
    static int guarded(Region r, int amount, boolean member, boolean heavy) {
        if (member) return 0;
        int fee = r == Region.LOCAL ? 6 : r == Region.PROVINCE ? 10 : 20;
        if (r == Region.REMOTE && heavy) fee += 15;
        if (amount >= 99) fee /= 2;
        return fee;
    }

    // 写法三：switch 表达式
    static int switched(Region r, int amount, boolean member, boolean heavy) {
        if (member) return 0;
        int fee = switch (r) {
            case LOCAL -> 6;
            case PROVINCE -> 10;
            case REMOTE -> heavy ? 35 : 20;
        };
        return amount >= 99 ? fee / 2 : fee;
    }

    // 写法四：查表
    static final Map<Region, Integer> BASE = new EnumMap<>(Map.of(Region.LOCAL, 6, Region.PROVINCE, 10, Region.REMOTE, 20));
    static int table(Region r, int amount, boolean member, boolean heavy) {
        if (member) return 0;
        int fee = BASE.get(r) + (r == Region.REMOTE && heavy ? 15 : 0);
        return amount >= 99 ? fee / 2 : fee;
    }

    // 写法五：每个地区一个策略对象
    interface RegionFee { int base(boolean heavy); }
    static final Map<Region, RegionFee> STRATEGIES = new EnumMap<>(Map.of(
            Region.LOCAL, heavy -> 6,
            Region.PROVINCE, heavy -> 10,
            Region.REMOTE, heavy -> heavy ? 35 : 20));
    static int strategy(Region r, int amount, boolean member, boolean heavy) {
        if (member) return 0;
        int fee = STRATEGIES.get(r).base(heavy);
        return amount >= 99 ? fee / 2 : fee;
    }

    public static void main(String[] args) {
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        int cases = 0, mismatches = 0;
        for (Region r : Region.values()) for (int amount : new int[]{0, 98, 99, 500}) for (boolean m : new boolean[]{true, false}) for (boolean h : new boolean[]{true, false}) {
            int expected = nested(r, amount, m, h); cases++;
            int[] others = {guarded(r, amount, m, h), switched(r, amount, m, h), table(r, amount, m, h), strategy(r, amount, m, h)};
            for (int o : others) if (o != expected) mismatches++;
        }
        System.out.println("equivalence\t" + cases + " 组输入（3 个地区 × 4 个金额 × 是否会员 × 是否超重），另外四种写法与嵌套写法不一致的次数：" + mismatches);
    }
}
