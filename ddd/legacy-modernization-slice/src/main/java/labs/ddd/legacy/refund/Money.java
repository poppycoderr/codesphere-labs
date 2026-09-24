package labs.ddd.legacy.refund;

/** 以分为单位的金额。 */
public record Money(long cents) {
    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("金额不能为负");
        }
    }
}
