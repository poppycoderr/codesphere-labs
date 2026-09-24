package labs.ddd.layered.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 场次聚合根：容量、已确认名单与候补队列在同一个一致性边界内。 */
public final class Session {

    private final SessionId id;
    private final int capacity;
    private final List<Attendee> confirmed;
    private final List<Attendee> waitlist;
    private final long version;
    private final List<DomainEvent> pending = new ArrayList<>();

    private Session(SessionId id, int capacity, List<Attendee> confirmed, List<Attendee> waitlist, long version) {
        this.id = id;
        this.capacity = capacity;
        this.confirmed = new ArrayList<>(confirmed);
        this.waitlist = new ArrayList<>(waitlist);
        this.version = version;
    }

    public static Session open(SessionId id, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("容量至少为 1");
        }
        return new Session(id, capacity, List.of(), List.of(), 0);
    }

    /** 从持久化状态恢复：不校验创建规则，也不登记事件。 */
    public static Session restore(SessionId id, int capacity, List<Attendee> confirmed, List<Attendee> waitlist, long version) {
        return new Session(id, capacity, confirmed, waitlist, version);
    }

    public void register(Attendee attendee) {
        boolean known = confirmed.stream().anyMatch(a -> a.attendeeId().equals(attendee.attendeeId()))
                || waitlist.stream().anyMatch(a -> a.attendeeId().equals(attendee.attendeeId()));
        if (known) {
            throw new RegistrationRefused("DUPLICATE");
        }
        if (confirmed.size() < capacity) {
            confirmed.add(attendee);
            pending.add(new DomainEvent.RegistrationConfirmed(id, attendee.attendeeId()));
        } else {
            waitlist.add(attendee);
            pending.add(new DomainEvent.CandidateWaitlisted(id, attendee.attendeeId(), waitlist.size()));
        }
    }

    public List<DomainEvent> drainEvents() {
        List<DomainEvent> out = List.copyOf(pending);
        pending.clear();
        return out;
    }

    public SessionId id() {
        return id;
    }

    public int capacity() {
        return capacity;
    }

    public List<Attendee> confirmed() {
        return Collections.unmodifiableList(confirmed);
    }

    public List<Attendee> waitlist() {
        return Collections.unmodifiableList(waitlist);
    }

    public long version() {
        return version;
    }
}
