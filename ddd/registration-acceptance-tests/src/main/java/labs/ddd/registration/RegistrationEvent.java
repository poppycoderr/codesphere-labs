package labs.ddd.registration;

import labs.ddd.registration.Ids.AttendeeId;
import labs.ddd.registration.Ids.SessionId;

/** 事件风暴墙上的橙色便利贴：报名上下文里已经发生的业务事实，过去式命名。 */
public sealed interface RegistrationEvent {

    SessionId sessionId();

    AttendeeId attendeeId();

    record RegistrationConfirmed(
            SessionId sessionId,
            AttendeeId attendeeId) implements RegistrationEvent {
    }

    record CandidateWaitlisted(
            SessionId sessionId,
            AttendeeId attendeeId,
            int position) implements RegistrationEvent {
    }

    record RegistrationCancelled(
            SessionId sessionId,
            AttendeeId attendeeId) implements RegistrationEvent {
    }

    record WaitlistLeft(
            SessionId sessionId,
            AttendeeId attendeeId) implements RegistrationEvent {
    }

    record CandidatePromoted(
            SessionId sessionId,
            AttendeeId attendeeId) implements RegistrationEvent {
    }
}
