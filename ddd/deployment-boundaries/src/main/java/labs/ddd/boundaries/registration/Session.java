package labs.ddd.boundaries.registration;

import java.util.ArrayList;
import java.util.List;

/** 报名上下文的场次聚合（简化）：容量、重复、候补。 */
public final class Session {

    public enum Outcome { CONFIRMED, WAITLISTED }

    private final String id;
    private final int capacity;
    private final List<String> confirmed = new ArrayList<>();
    private final List<String> waitlist = new ArrayList<>();

    public Session(String id, int capacity) {
        this.id = id;
        this.capacity = capacity;
    }

    public Outcome register(String attendee) {
        if (confirmed.contains(attendee) || waitlist.contains(attendee)) {
            throw new IllegalStateException("DUPLICATE");
        }
        if (confirmed.size() < capacity) {
            confirmed.add(attendee);
            return Outcome.CONFIRMED;
        }
        waitlist.add(attendee);
        return Outcome.WAITLISTED;
    }

    public String id() {
        return id;
    }

    public Session copy() {
        Session s = new Session(id, capacity);
        s.confirmed.addAll(confirmed);
        s.waitlist.addAll(waitlist);
        return s;
    }

    public int confirmedCount() {
        return confirmed.size();
    }
}
