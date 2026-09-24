package labs.ddd.values;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** 金额值对象：构造时统一到币种的小数位，保证「100.0 元」和「100.00 元」是同一个值。 */
public record Money(
        BigDecimal amount,
        Currency currency) {

    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负：" + amount);
        }
        try {
            amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(currency + " 最多 " + currency.getDefaultFractionDigits() + " 位小数：" + amount);
        }
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currency));
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("币种不同不能相加：" + currency + " + " + other.currency);
        }
        return new Money(amount.add(other.amount), currency);
    }
}
