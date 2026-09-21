import java.math.BigDecimal;
import java.util.*;
public class Rules {
    interface DiscountRule { BigDecimal apply(BigDecimal price); }
    record Percent(BigDecimal rate) implements DiscountRule {
        public BigDecimal apply(BigDecimal p) { return p.multiply(BigDecimal.ONE.subtract(rate)); }
    }
    record Cap(DiscountRule inner, BigDecimal maxOff) implements DiscountRule {
        public BigDecimal apply(BigDecimal p) { return p.subtract(p.subtract(inner.apply(p)).min(maxOff)); }
    }
    public static void main(String[] a) throws Exception {
        System.out.println("addAll 声明在：" + HashSet.class.getMethod("addAll", Collection.class).getDeclaringClass().getName());
        DiscountRule r = new Cap(new Percent(new BigDecimal("0.2")), new BigDecimal("30"));
        System.out.println("100 元打 8 折封顶减 30：" + r.apply(new BigDecimal("100")));
        System.out.println("500 元打 8 折封顶减 30：" + r.apply(new BigDecimal("500")));
    }
}
