package labs.ddd.layered.application;

import java.util.List;
import labs.ddd.layered.domain.DomainEvent;

/** 用例输出：只暴露调用方需要的结果，不返回聚合本身。 */
public record RegistrationResult(
        String outcome,
        int waitlistPosition) {

    static RegistrationResult from(List<DomainEvent> events) {
        return switch (events.getFirst()) {
            case DomainEvent.RegistrationConfirmed e -> new RegistrationResult("CONFIRMED", 0);
            case DomainEvent.CandidateWaitlisted e -> new RegistrationResult("WAITLISTED", e.position());
        };
    }
}
