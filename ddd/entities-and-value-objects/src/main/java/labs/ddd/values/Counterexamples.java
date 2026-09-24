package labs.ddd.values;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** 对照组：看起来像实体或值对象，但相等性或不可变性有漏洞的写法。 */
public final class Counterexamples {
    private Counterexamples() {
    }

    /** 直接用 record 包 BigDecimal：equals 比较 scale。 */
    public record RawMoney(
            BigDecimal amount,
            Currency currency) {
    }

    /** record 只保证字段不被重新赋值，列表本身仍可变。 */
    public record Tags(List<String> values) {
    }

    /** 构造时复制，得到不可修改的列表。 */
    public record CopiedTags(List<String> values) {
        public CopiedTags {
            values = List.copyOf(values);
        }
    }

    /** 数组组件：record 的 equals 对数组比较引用。 */
    public record SeatNumbers(int[] numbers) {
    }

    /** 标识由数据库在保存时分配，保存前为 null。 */
    public static final class DbAssignedRegistration {
        private Long id;
        private final String attendee;

        public DbAssignedRegistration(String attendee) {
            this.attendee = attendee;
        }

        public void assignId(long id) {
            this.id = id;
        }

        public String attendee() {
            return attendee;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DbAssignedRegistration other && Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }
}
