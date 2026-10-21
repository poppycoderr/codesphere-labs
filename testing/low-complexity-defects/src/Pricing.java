/** 三个没有分支的计价方法：圈复杂度都是 1，每个都有一个只在边界输入下出现的错误。 */
public class Pricing {

    /** 折后金额（分）：先除以 100 再乘，百分比被整数除法截成 0。 */
    static long discounted(long priceCents, int qty, int percentOff) {
        return priceCents * qty * ((100 - percentOff) / 100);
    }

    /** 订单金额（分）：用 int 相乘，大额订单溢出成负数。 */
    static int total(int priceCents, int qty) {
        return priceCents * qty;
    }

    /** 平均单价（元，保留两位）：整数除法先把小数丢了。 */
    static double averageYuan(long totalCents, int qty) {
        return (totalCents / qty) / 100.0;
    }

    record Case(String name, Object actual, Object expected) {
        boolean ok() {
            return actual.equals(expected);
        }
    }

    static String run(Case... cases) {
        int failed = 0;
        StringBuilder detail = new StringBuilder();
        for (Case c : cases) {
            if (!c.ok()) {
                failed++;
                detail.append("；").append(c.name()).append(" 期望 ").append(c.expected()).append(" 实际 ").append(c.actual());
            }
        }
        return (cases.length - failed) + "/" + cases.length + " 通过" + detail;
    }

    public static void main(String[] args) {
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        // 常见输入：每个方法都被执行到，测试全部通过
        System.out.println("typical\t常见输入（不打折、小额、能整除）：" + run(
                new Case("discounted(1999, 2, 0)", discounted(1999, 2, 0), 3998L),
                new Case("total(1999, 3)", total(1999, 3), 5997),
                new Case("averageYuan(3000, 3)", averageYuan(3000, 3), 10.0)));
        // 边界输入：每个方法各一个
        System.out.println("boundary\t边界输入：" + run(
                new Case("discounted(1999, 2, 10)", discounted(1999, 2, 10), 3598L),
                new Case("total(2000000, 1500)", total(2000000, 1500), 3000000000L),
                new Case("averageYuan(1000, 6)", averageYuan(1000, 6), 1.67)));
    }
}
