package labs.ddd.layered.infrastructure;

import java.util.List;
import java.util.stream.IntStream;
import labs.ddd.layered.domain.Attendee;
import labs.ddd.layered.domain.Phone;
import labs.ddd.layered.domain.Session;
import labs.ddd.layered.domain.SessionId;

/** Session ↔ SessionRecord 的显式双向转换。重建走 Session.restore，不经过创建规则。 */
public class SessionConverter {

    public SessionRecord toRecord(Session s) {
        return new SessionRecord(s.id().value(), s.capacity(), s.version(), rows(s.confirmed()), rows(s.waitlist()));
    }

    public Session toDomain(SessionRecord r) {
        return Session.restore(new SessionId(r.id()), r.capacity(), attendees(r.confirmed()), attendees(r.waitlist()), r.version());
    }

    protected static List<SessionRecord.AttendeeRow> rows(List<Attendee> attendees) {
        return IntStream.range(0, attendees.size())
                .mapToObj(i -> new SessionRecord.AttendeeRow(attendees.get(i).attendeeId(), attendees.get(i).phone().value(), i + 1))
                .toList();
    }

    protected static List<Attendee> attendees(List<SessionRecord.AttendeeRow> rows) {
        return rows.stream().map(r -> new Attendee(r.attendeeId(), new Phone(r.phone()))).toList();
    }
}
