package labs.ddd.layered.domain;

/** 报名上下文的领域事件。 */
public sealed interface DomainEvent {

    record RegistrationConfirmed(
            SessionId sessionId,
            String attendeeId) implements DomainEvent {
    }

    record CandidateWaitlisted(
            SessionId sessionId,
            String attendeeId,
            int position) implements DomainEvent {
    }
}
