import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 重复的是同一条知识，还是只是长得像；把重复的代码提取成方法时，哪些行为会悄悄改变。
 * 每个场景都把「改之前」和「改之后」放在一起运行，输出两者的结果。
 */
public class DuplicationLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    // 一、同一条知识写在两处：税率
    static final class InvoiceV1 { static BigDecimal total(BigDecimal net) { return net.multiply(new BigDecimal("1.06")).setScale(2, RoundingMode.HALF_UP); } }
    static final class RefundV1 { static BigDecimal amount(BigDecimal net) { return net.multiply(new BigDecimal("1.06")).setScale(2, RoundingMode.HALF_UP); } }
    static final class InvoiceV2 { static BigDecimal total(BigDecimal net) { return net.multiply(new BigDecimal("1.09")).setScale(2, RoundingMode.HALF_UP); } }   // 税率调整，只改到了开票
    static final class TaxPolicy { static BigDecimal rate = new BigDecimal("0.06"); static BigDecimal withTax(BigDecimal net) { return net.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP); } }

    // 二、长得像、但不是同一条规则：昵称与优惠码
    static boolean nicknameV1(String s) { return s.matches("[A-Za-z0-9]{4,20}"); }
    static boolean couponV1(String s) { return s.matches("[A-Za-z0-9]{4,20}"); }
    static String shortTextRule = "[A-Za-z0-9]{4,20}";
    static boolean isValidShortText(String s) { return s.matches(shortTextRule); }   // 合并之后两处都调用它

    // 三、提取时改变了失败语义
    static final class DbError extends RuntimeException { DbError() { super("写入失败"); } }
    static final class NotifierDown extends RuntimeException { NotifierDown() { super("通知服务不可用"); } }
    static void failingNotifier(Exception e) { throw new NotifierDown(); }
    static void inlineVersion() {
        try { throw new DbError(); }
        catch (DbError e) { try { failingNotifier(e); } catch (RuntimeException ignored) { } throw e; }   // 原写法：通知失败被忽略，原异常照常抛出
    }
    static void runAndNotify(Runnable task) {
        try { task.run(); } catch (RuntimeException e) { failingNotifier(e); throw e; }                    // 提取出的公共方法：少了内层 try
    }
    static void runAndNotifyFixed(Runnable task) {
        try { task.run(); } catch (RuntimeException e) {
            try { failingNotifier(e); } catch (RuntimeException n) { e.addSuppressed(n); }
            throw e;
        }
    }

    // 四、提取时改变了求值次数
    static final AtomicInteger expensiveCalls = new AtomicInteger(), quotaUsed = new AtomicInteger();
    static String expensive() { expensiveCalls.incrementAndGet(); return "dump"; }
    static void logIf(boolean on, String message) { if (on) System.err.println(message); }
    static void logIf(boolean on, Supplier<String> message) { if (on) System.err.println(message.get()); }
    static boolean cheapCheck() { return false; }
    static boolean consumeQuota() { quotaUsed.incrementAndGet(); return true; }
    static boolean both(boolean a, boolean b) { return a && b; }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));
        BigDecimal net = new BigDecimal("100.00");
        out("knowledge.before", "开票与退款各写一遍税率 6%：开票 " + InvoiceV1.total(net) + "，退款 " + RefundV1.amount(net));
        out("knowledge.change", "税率改成 9%，只改到了开票：开票 " + InvoiceV2.total(net) + "，退款仍是 " + RefundV1.amount(net));
        TaxPolicy.rate = new BigDecimal("0.09");
        out("knowledge.single_source", "两处都调用同一个 TaxPolicy，改一处：开票 " + TaxPolicy.withTax(net) + "，退款 " + TaxPolicy.withTax(net));

        out("similar.before", "昵称与优惠码今天的规则一样（4 到 20 位字母数字）：昵称 \"bob2026\" " + nicknameV1("bob2026") + "，优惠码 \"七折优惠\" " + couponV1("七折优惠"));
        shortTextRule = "[\\p{IsHan}A-Za-z0-9]{2,30}";                    // 产品要求昵称支持中文、最多 30 字，改了合并后的方法
        out("similar.merged_change", "合并成 isValidShortText 之后按昵称的新需求修改：昵称 \"小明\" " + isValidShortText("小明") + "，优惠码 \"七折优惠\" 也变成 " + isValidShortText("七折优惠"));

        out("failure.inline", "原写法：" + attempt(DuplicationLab::inlineVersion));
        out("failure.extracted", "提取成 runAndNotify 之后：" + attempt(() -> runAndNotify(() -> { throw new DbError(); })));
        out("failure.fixed", "提取时把通知失败挂到原异常上：" + attempt(() -> runAndNotifyFixed(() -> { throw new DbError(); })));

        for (int i = 0; i < 1000; i++) if (false) System.err.println(expensive());
        int inlineCalls = expensiveCalls.getAndSet(0);
        for (int i = 0; i < 1000; i++) logIf(false, expensive());
        int extractedCalls = expensiveCalls.getAndSet(0);
        for (int i = 0; i < 1000; i++) logIf(false, () -> expensive());
        out("evaluation.eager", "日志关闭时循环 1000 次：内联的 if 里 expensive() 调用 " + inlineCalls + " 次；提取成 logIf(on, expensive()) 后 " + extractedCalls
                + " 次；参数改成 Supplier 后 " + expensiveCalls.get() + " 次");
        for (int i = 0; i < 100; i++) if (cheapCheck() && consumeQuota()) { }
        int shortCircuit = quotaUsed.getAndSet(0);
        for (int i = 0; i < 100; i++) if (both(cheapCheck(), consumeQuota())) { }
        out("evaluation.short_circuit", "cheapCheck() && consumeQuota() 执行 100 次，额度被扣 " + shortCircuit + " 次；提取成 both(cheapCheck(), consumeQuota()) 后被扣 " + quotaUsed.get() + " 次");
    }

    static String attempt(Runnable r) {
        try { r.run(); return "没有异常"; }
        catch (RuntimeException e) { return "调用方收到 " + e.getClass().getSimpleName() + "（" + e.getMessage() + "）" + (e.getSuppressed().length > 0 ? "，suppressed 里有 " + e.getSuppressed()[0].getClass().getSimpleName() : ""); }
    }
}
