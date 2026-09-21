import java.math.BigDecimal;

public class Sealed {
    sealed interface Discount permits Percentage, FixedAmount, NoDiscount, BuyNGetOne {}
    record Percentage(BigDecimal rate) implements Discount {}
    record FixedAmount(BigDecimal amount) implements Discount {}
    record NoDiscount() implements Discount {}
    record BuyNGetOne(int n) implements Discount {}

    static BigDecimal apply(Discount d, BigDecimal price) {
        return switch (d) {                              // 没有 default：编译器检查是否穷举
            case Percentage p -> price.multiply(BigDecimal.ONE.subtract(p.rate()));
            case FixedAmount f -> price.subtract(f.amount()).max(BigDecimal.ZERO);
            case NoDiscount n -> price;
        };
    }
    public static void main(String[] a) {
        System.out.println(apply(new Percentage(new BigDecimal("0.2")), new BigDecimal("100")));
        System.out.println(apply(new FixedAmount(new BigDecimal("30")), new BigDecimal("20")));
    }
}
