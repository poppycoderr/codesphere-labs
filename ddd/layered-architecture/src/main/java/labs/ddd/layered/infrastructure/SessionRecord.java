package labs.ddd.layered.infrastructure;

import java.util.List;

/** 持久化形状：一行场次加两个子表，字段全部公开，可以为任何值，由转换器负责进出领域。 */
public record SessionRecord(
        String id,
        int capacity,
        long version,
        List<AttendeeRow> confirmed,
        List<AttendeeRow> waitlist) {

    public record AttendeeRow(
            String attendeeId,
            String phone,
            int position) {
    }
}
