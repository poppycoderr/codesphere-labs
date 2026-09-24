package labs.ddd.registration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import labs.ddd.registration.Ids.AttendeeId;
import labs.ddd.registration.Ids.SessionId;
import labs.ddd.registration.RegistrationEvent.CandidatePromoted;
import labs.ddd.registration.RegistrationEvent.CandidateWaitlisted;
import labs.ddd.registration.RegistrationEvent.RegistrationCancelled;
import labs.ddd.registration.RegistrationEvent.RegistrationConfirmed;
import labs.ddd.registration.RegistrationEvent.WaitlistLeft;
import labs.ddd.registration.RegistrationRefused.Reason;

/**
 * 第一次讨论后直接照着便利贴写出的初稿：只有「报名—候补—取消—递补」的主线，
 * 没有贴出「同一个人重复点报名」这个热点，因此也没有 R3。
 */
public final class DraftSession implements RegistrationModel {

    private final SessionId id;
    private final int capacity;
    private final Instant startsAt;
    private final List<AttendeeId> confirmed = new ArrayList<>();
    private final Deque<AttendeeId> waitlist = new ArrayDeque<>();
    private boolean closed;

    public DraftSession(SessionId id, int capacity, Instant startsAt) {
        this.id = id;
        this.capacity = capacity;
        this.startsAt = startsAt;
    }

    @Override
    public List<RegistrationEvent> register(AttendeeId attendee, Instant now) {
        if (closed) {
            throw new RegistrationRefused(Reason.SESSION_CLOSED);
        }
        if (confirmed.size() < capacity) {
            confirmed.add(attendee);
            return List.of(new RegistrationConfirmed(id, attendee));
        }
        waitlist.addLast(attendee);
        return List.of(new CandidateWaitlisted(id, attendee, waitlist.size()));
    }

    @Override
    public List<RegistrationEvent> cancel(AttendeeId attendee, Instant now) {
        if (waitlist.remove(attendee)) {
            return List.of(new WaitlistLeft(id, attendee));
        }
        if (!confirmed.contains(attendee)) {
            throw new RegistrationRefused(Reason.NOT_REGISTERED);
        }
        if (now.isAfter(startsAt.minus(Duration.ofHours(24)))) {
            throw new RegistrationRefused(Reason.TOO_LATE_TO_CANCEL);
        }
        confirmed.remove(attendee);
        AttendeeId next = waitlist.pollFirst();
        if (next == null) {
            return List.of(new RegistrationCancelled(id, attendee));
        }
        confirmed.add(next);
        return List.of(new RegistrationCancelled(id, attendee), new CandidatePromoted(id, next));
    }

    @Override
    public void closeRegistration() {
        closed = true;
    }
}
