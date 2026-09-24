package labs.ddd.values;

import java.time.Instant;
import java.util.Objects;

/** 场次时间段：开始早于结束，左闭右开。 */
public record TimeSlot(
        Instant start,
        Instant end) {

    public TimeSlot {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("开始时间必须早于结束时间：" + start + " / " + end);
        }
    }

    public boolean overlaps(TimeSlot other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
