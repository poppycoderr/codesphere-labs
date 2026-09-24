package labs.ddd.values;

import java.util.Objects;
import labs.ddd.values.Ids.AttendeeId;
import labs.ddd.values.Ids.RegistrationId;
import labs.ddd.values.Ids.SessionId;

/** 报名实体：按标识相等。联系电话可以改，改完仍是同一个报名。 */
public final class Registration {

    private final RegistrationId id;
    private final SessionId sessionId;
    private final AttendeeId attendeeId;
    private Phone contact;

    public Registration(RegistrationId id, SessionId sessionId, AttendeeId attendeeId, Phone contact) {
        this.id = Objects.requireNonNull(id);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.attendeeId = Objects.requireNonNull(attendeeId);
        this.contact = Objects.requireNonNull(contact);
    }

    public void changeContact(Phone phone) {
        this.contact = Objects.requireNonNull(phone);
    }

    public RegistrationId id() {
        return id;
    }

    public Phone contact() {
        return contact;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Registration other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
