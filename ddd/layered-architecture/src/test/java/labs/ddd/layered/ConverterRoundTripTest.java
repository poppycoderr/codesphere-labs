package labs.ddd.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import labs.ddd.layered.domain.Session;
import labs.ddd.layered.infrastructure.SessionConverter;
import labs.ddd.layered.infrastructure.SessionRecord;
import labs.ddd.layered.infrastructure.SessionRecord.AttendeeRow;
import org.junit.jupiter.api.Test;

/** 转换器的往返检查：record → domain → record 必须不丢字段。 */
class ConverterRoundTripTest {

    /** 常见的疏漏：新增候补表之后，转换器只映射了已确认名单。 */
    static final class ForgetfulConverter extends SessionConverter {
        @Override
        public Session toDomain(SessionRecord r) {
            return Session.restore(new labs.ddd.layered.domain.SessionId(r.id()), r.capacity(), attendees(r.confirmed()), List.of(), r.version());
        }
    }

    static List<SessionRecord> samples() {
        Random random = new Random(20260924);
        List<SessionRecord> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int capacity = 1 + random.nextInt(5);
            int registered = random.nextInt(capacity + 4);
            List<AttendeeRow> confirmed = new ArrayList<>(), waitlist = new ArrayList<>();
            for (int k = 0; k < registered; k++) {
                AttendeeRow row = new AttendeeRow("a" + k, "13" + String.format("%09d", random.nextInt(1_000_000_000)), 0);
                (k < capacity ? confirmed : waitlist).add(row);
            }
            out.add(new SessionRecord("S-" + i, capacity, random.nextInt(10), renumber(confirmed), renumber(waitlist)));
        }
        return out;
    }

    static List<AttendeeRow> renumber(List<AttendeeRow> rows) {
        List<AttendeeRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(new AttendeeRow(rows.get(i).attendeeId(), rows.get(i).phone(), i + 1));
        }
        return out;
    }

    static long mismatches(SessionConverter converter) {
        return samples().stream().filter(r -> !r.equals(converter.toRecord(converter.toDomain(r)))).count();
    }

    @Test
    void roundTripKeepsEveryField() {
        long good = mismatches(new SessionConverter());
        long broken = mismatches(new ForgetfulConverter());
        long withWaitlist = samples().stream().filter(r -> !r.waitlist().isEmpty()).count();
        assertEquals(0, good);
        assertNotEquals(0, broken);
        assertEquals(withWaitlist, broken);
        Facts.record("converter", "200 个随机场次往返：完整转换器不一致 " + good + " 个；漏掉候补表的转换器不一致 " + broken + " 个（等于有候补的场次数 " + withWaitlist + "）");
    }
}
