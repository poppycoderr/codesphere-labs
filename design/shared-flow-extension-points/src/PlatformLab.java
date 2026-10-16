import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 多个产品（课程、会员、实物）共用一套下单流程。三种写法：共用流程里按产品 if 分支、每一步都可替换的流水线、
 * 平台持有骨架与不可替换的步骤、产品只实现声明好的扩展点。比较新增产品要改的地方、产品能否跳过平台规则、
 * 平台契约测试能否发现扩展的错误。所有输入固定。
 */
public class PlatformLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static final class Order {
        final String product; final BigDecimal amount; final List<String> trail = new ArrayList<>(); boolean riskChecked; BigDecimal fee;
        Order(String p, String a) { product = p; amount = new BigDecimal(a); }
    }

    // ---------- 写法一：共用流程里按产品分支 ----------
    static final String IF_FLOW = """
            void place(Order o) {
                if (o.product.equals("COURSE")) checkSeat(o);
                if (o.product.equals("GOODS")) reserveStock(o);
                riskCheck(o);
                if (o.product.equals("MEMBERSHIP")) fee = BigDecimal.ZERO; else fee = amount.multiply(RATE);
                if (o.product.equals("GOODS")) createShipment(o);
                if (o.product.equals("COURSE") || o.product.equals("MEMBERSHIP")) grantAccess(o);
            }
            """;

    // ---------- 写法二：每一步都可替换的流水线 ----------
    static List<UnaryOperator<Order>> defaultSteps() {
        List<UnaryOperator<Order>> s = new ArrayList<>();
        s.add(o -> { o.trail.add("validate"); return o; });
        s.add(o -> { o.trail.add("risk"); o.riskChecked = true; return o; });
        s.add(o -> { o.trail.add("pay"); return o; });
        return s;
    }

    // ---------- 写法三：平台骨架 + 声明的扩展点 ----------
    interface ProductExtension {
        String product();
        void prepare(Order o);                         // 扩展点：下单前准备（占座、锁库存）
        BigDecimal fee(Order o);                       // 扩展点：手续费，契约：0 ≤ fee ≤ amount
        void fulfil(Order o);                          // 扩展点：履约（开通、发货）
    }
    static final class Platform {
        final Map<String, ProductExtension> ext = new LinkedHashMap<>();
        void register(ProductExtension e) { ext.put(e.product(), e); }
        /** 骨架由平台持有：风控与支付不是扩展点，产品无法替换或跳过 */
        final void place(Order o) {
            ProductExtension e = ext.get(o.product);
            if (e == null) throw new IllegalArgumentException("未注册的产品：" + o.product);
            e.prepare(o); o.trail.add("prepare");
            o.riskChecked = true; o.trail.add("risk");
            o.fee = e.fee(o); o.trail.add("pay");
            e.fulfil(o); o.trail.add("fulfil");
        }
    }
    record Simple(String product, String feeRate) implements ProductExtension {
        public void prepare(Order o) { }
        public BigDecimal fee(Order o) { return o.amount.multiply(new BigDecimal(feeRate)); }
        public void fulfil(Order o) { }
    }

    /** 平台的契约测试：对每个已注册的扩展跑同一组检查 */
    static List<String> contract(Platform p) {
        List<String> failures = new ArrayList<>();
        for (ProductExtension e : p.ext.values()) {
            for (String amt : new String[] { "100", "0", "-50" }) {          // -50 代表退款单
                BigDecimal f = e.fee(new Order(e.product(), amt));
                BigDecimal a = new BigDecimal(amt);
                boolean ok = a.signum() >= 0 ? f.signum() >= 0 && f.compareTo(a) <= 0 : f.signum() <= 0 && f.compareTo(a) >= 0;
                if (!ok) failures.add(e.product() + " 金额 " + amt + " 的手续费 " + f.stripTrailingZeros().toPlainString());
            }
        }
        return failures;
    }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、按产品分支：新增产品要找到所有分支
        long branches = IF_FLOW.lines().filter(l -> l.contains("o.product.equals")).count();
        out("if.branches", "共用流程里按产品判断的分支 " + branches + " 处（三种产品）；新增「直播」时，每一处都要判断要不要加，按这段代码，漏改时直播会落到 else 按默认费率收手续费，也不会开通权限");

        // 二、每一步都可替换：产品替换了平台的风控
        List<UnaryOperator<Order>> steps = defaultSteps();
        steps.set(1, o -> { o.trail.add("fast-approve"); return o; });       // 某个产品为了转化率替换了风控步骤
        Order fast = new Order("FLASH_SALE", "100");
        for (var s : steps) s.apply(fast);
        out("pipeline.skip", "流水线允许产品替换任一步骤，秒杀产品换掉了第 2 步：执行轨迹 " + fast.trail + "，riskChecked=" + fast.riskChecked);

        // 三、平台骨架：风控不是扩展点
        Platform p = new Platform();
        p.register(new Simple("COURSE", "0.05"));
        p.register(new Simple("MEMBERSHIP", "0"));
        Order c = new Order("COURSE", "200"); p.place(c);
        out("skeleton.flow", "平台骨架下单课程：轨迹 " + c.trail + "，riskChecked=" + c.riskChecked + "，手续费 " + c.fee.stripTrailingZeros().toPlainString() + "；产品只能实现 prepare、fee、fulfil 三个扩展点");
        p.register(new Simple("LIVE", "0.03"));
        Order live = new Order("LIVE", "100"); p.place(live);
        out("skeleton.new_product", "新增直播：注册一个扩展，平台代码不变；直播订单轨迹 " + live.trail);
        String unknown;
        try { p.place(new Order("GIFT_CARD", "100")); unknown = "成功"; } catch (IllegalArgumentException e) { unknown = "被拒绝（" + e.getMessage() + "）"; }
        out("skeleton.unknown", "没有注册扩展的产品下单：" + unknown + "，而不是落到某个默认分支");

        // 四、契约测试：对所有扩展跑同一组检查
        p.register(new Simple("GOODS", "0.06"));
        p.register(new ProductExtension() {                                 // 实物团队自己写的手续费：退款也按正数收
            public String product() { return "GROUP_BUY"; }
            public void prepare(Order o) { }
            public BigDecimal fee(Order o) { return o.amount.abs().multiply(new BigDecimal("0.06")); }
            public void fulfil(Order o) { }
        });
        List<String> f = contract(p);
        out("contract", "平台对 " + p.ext.size() + " 个扩展跑手续费契约（0 ≤ 手续费 ≤ 金额，退款单反号）：失败 " + f.size() + " 项 " + f);
    }
}
