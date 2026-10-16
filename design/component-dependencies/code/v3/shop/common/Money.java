package shop.common;
import shop.order.Order;
public final class Money {
    public static String format(long cents) { return String.format("%d.%02d", cents / 100, cents % 100); }
    /** 为了方便，把「订单金额格式化」也放进了 common */
    public static String of(Order o) { return format(o.amount()); }
}
