import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;

/**
 * 拆一个长方法：同一组随机订单分别交给原方法与四个拆分后的版本，比较返回值、抛出的异常与副作用的顺序。
 * 原方法与「正确拆分」的版本行为一致；另外三个版本各自犯了一个拆分时常见的错误，比较器把差异找出来。
 */
public class ExtractionLab {
    record Line(String sku, int qty, int price) { }
    record Order(String id, List<Line> lines, String coupon, boolean vip) { }

    /** 记录副作用的顺序：预占库存、写审计、发消息 */
    static final class World {
        final List<String> effects = new ArrayList<>();
        int stock(String sku) { return sku.endsWith("9") ? 0 : 100; }
        void reserve(String sku, int qty) { effects.add("reserve " + sku + "x" + qty); }
        void audit(String msg) { effects.add("audit " + msg); }
    }

    // 原方法：校验、计价、预占、审计混在一起
    static int original(Order o, World w) {
        if (o.lines().isEmpty()) throw new IllegalArgumentException("empty order");
        int total = 0;
        for (Line l : o.lines()) {
            if (l.qty() <= 0) throw new IllegalArgumentException("bad qty " + l.sku());
            if (w.stock(l.sku()) < l.qty()) throw new IllegalStateException("out of stock " + l.sku());
            total += l.qty() * l.price();
        }
        if (o.coupon() != null && o.coupon().startsWith("OFF") && total >= 100) total -= 20;
        if (o.vip()) total = total * 95 / 100;
        for (Line l : o.lines()) w.reserve(l.sku(), l.qty());
        w.audit(o.id() + " total=" + total);
        return total;
    }

    // 正确拆分：每一步保持原来的顺序与条件
    static int extracted(Order o, World w) {
        validate(o, w);
        int total = applyVip(o, applyCoupon(o, subtotal(o)));
        reserveAll(o, w);
        w.audit(o.id() + " total=" + total);
        return total;
    }
    static void validate(Order o, World w) {
        if (o.lines().isEmpty()) throw new IllegalArgumentException("empty order");
        for (Line l : o.lines()) {
            if (l.qty() <= 0) throw new IllegalArgumentException("bad qty " + l.sku());
            if (w.stock(l.sku()) < l.qty()) throw new IllegalStateException("out of stock " + l.sku());
        }
    }
    static int subtotal(Order o) { int t = 0; for (Line l : o.lines()) t += l.qty() * l.price(); return t; }
    static int applyCoupon(Order o, int total) { return o.coupon() != null && o.coupon().startsWith("OFF") && total >= 100 ? total - 20 : total; }
    static int applyVip(Order o, int total) { return o.vip() ? total * 95 / 100 : total; }
    static void reserveAll(Order o, World w) { for (Line l : o.lines()) w.reserve(l.sku(), l.qty()); }

    // 错误一：把「预占」并进校验循环，库存不足时前面几行已经预占了
    static int mergedLoop(Order o, World w) {
        if (o.lines().isEmpty()) throw new IllegalArgumentException("empty order");
        for (Line l : o.lines()) {
            if (l.qty() <= 0) throw new IllegalArgumentException("bad qty " + l.sku());
            if (w.stock(l.sku()) < l.qty()) throw new IllegalStateException("out of stock " + l.sku());
            w.reserve(l.sku(), l.qty());
        }
        int total = applyVip(o, applyCoupon(o, subtotal(o)));
        w.audit(o.id() + " total=" + total);
        return total;
    }

    // 错误二：两个折扣的先后对调了（「看起来是两个独立的折扣」）
    static int swappedDiscounts(Order o, World w) {
        validate(o, w);
        int total = applyCoupon(o, applyVip(o, subtotal(o)));
        reserveAll(o, w);
        w.audit(o.id() + " total=" + total);
        return total;
    }

    // 错误三：校验改成先检查所有数量、再检查所有库存，报出的第一个错误变了
    static int splitValidation(Order o, World w) {
        if (o.lines().isEmpty()) throw new IllegalArgumentException("empty order");
        for (Line l : o.lines()) if (l.qty() <= 0) throw new IllegalArgumentException("bad qty " + l.sku());
        for (Line l : o.lines()) if (w.stock(l.sku()) < l.qty()) throw new IllegalStateException("out of stock " + l.sku());
        int total = applyVip(o, applyCoupon(o, subtotal(o)));
        reserveAll(o, w);
        w.audit(o.id() + " total=" + total);
        return total;
    }

    record Outcome(String result, List<String> effects) { }
    static Outcome run(Function<World, Integer> f) {
        World w = new World();
        String r;
        try { r = "returned " + f.apply(w); } catch (RuntimeException e) { r = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        return new Outcome(r, w.effects);
    }

    interface Impl { int apply(Order o, World w); }

    public static void main(String[] args) {
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        Random rnd = new Random(20261016L);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            List<Line> lines = new ArrayList<>();
            int n = rnd.nextInt(4);
            for (int k = 0; k < n; k++) lines.add(new Line("S" + rnd.nextInt(10), rnd.nextInt(5) - 1 + (rnd.nextInt(10) == 0 ? 0 : 1), 10 + rnd.nextInt(90)));
            orders.add(new Order("O" + i, lines, rnd.nextInt(3) == 0 ? "OFF20" : null, rnd.nextBoolean()));
        }
        System.out.println("setup\t2000 个随机订单（固定种子）：含空订单、数量为 0 或负数、缺货商品、优惠码与会员的各种组合");
        Object[][] impls = {{"extracted", "按原顺序拆成 validate、subtotal、applyCoupon、applyVip、reserveAll", (Impl) ExtractionLab::extracted},
                {"merged_loop", "把预占并进校验循环", (Impl) ExtractionLab::mergedLoop},
                {"swapped_discounts", "两个折扣的先后对调", (Impl) ExtractionLab::swappedDiscounts},
                {"split_validation", "先查完所有数量、再查所有库存", (Impl) ExtractionLab::splitValidation}};
        for (Object[] impl : impls) {
            Impl f = (Impl) impl[2];
            int diffResult = 0, diffEffects = 0; String first = null;
            for (Order o : orders) {
                Outcome a = run(w -> original(o, w)), b = run(w -> f.apply(o, w));
                boolean r = !a.result().equals(b.result()), e = !Objects.equals(a.effects(), b.effects());
                if (r) diffResult++;
                if (e) diffEffects++;
                if ((r || e) && first == null) first = o.id() + "：原方法 [" + a.result() + "；" + String.join("，", a.effects()) + "]，新版本 [" + b.result() + "；" + String.join("，", b.effects()) + "]";
            }
            System.out.println(impl[0] + "\t" + impl[1] + "：返回值或异常不同 " + diffResult + " 个，副作用不同 " + diffEffects + " 个" + (first == null ? "" : "；第一个差异 " + first));
        }
    }
}
