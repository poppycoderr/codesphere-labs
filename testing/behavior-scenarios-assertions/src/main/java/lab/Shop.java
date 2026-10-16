package lab;

import java.math.BigDecimal;
import java.util.List;

/** 被测代码：运费规则（有一个边界错误）、购物车（少返回一件商品）、订单摘要（两个字段算错）、两版计价（第二版只是重构） */
public final class Shop {
    private Shop() { }

    /** 规则：不满 10 公斤运费 10，10 公斤及以上运费 20。这里把 >= 写成了 > */
    public static int shippingFee(int kg) { return kg > 10 ? 20 : 10; }

    public static List<String> cartItems() { return List.of("apple", "pear"); }   // 应该还有 banana

    public record OrderSummary(String customer, BigDecimal total, String status) { }
    public static OrderSummary summary() { return new OrderSummary("alice", new BigDecimal("90.00"), "NEW"); }   // 应为 100.00、PAID

    public interface TaxService { BigDecimal rate(); }
    public record Line(String sku, BigDecimal price, int qty) { }

    /** 第一版：每一行都查一次税率 */
    public static BigDecimal totalV1(List<Line> lines, TaxService tax) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) sum = sum.add(l.price().multiply(BigDecimal.valueOf(l.qty())).multiply(BigDecimal.ONE.add(tax.rate())));
        return sum.setScale(2, java.math.RoundingMode.HALF_UP);
    }
    /** 第二版（重构）：税率只查一次，结果相同 */
    public static BigDecimal totalV2(List<Line> lines, TaxService tax) {
        BigDecimal factor = BigDecimal.ONE.add(tax.rate());
        return lines.stream().map(l -> l.price().multiply(BigDecimal.valueOf(l.qty())).multiply(factor))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
