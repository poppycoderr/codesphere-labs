package labs.ddd.layered.application;

import java.util.List;
import labs.ddd.layered.domain.Attendee;
import labs.ddd.layered.domain.DomainEvent;
import labs.ddd.layered.domain.Phone;
import labs.ddd.layered.domain.RegistrationRefused;
import labs.ddd.layered.domain.Session;
import labs.ddd.layered.domain.SessionId;
import labs.ddd.layered.domain.SessionRepository;
import labs.ddd.layered.observability.Trace;

/** 应用服务：事务入口与用例顺序。容量、重复、候补规则都在 Session 里，这里一条也不写。 */
public final class RegisterForSession {

    private final SessionRepository sessions;
    private final Transactions transactions;
    private final EventPublisher publisher;

    public RegisterForSession(SessionRepository sessions, Transactions transactions, EventPublisher publisher) {
        this.sessions = sessions;
        this.transactions = transactions;
        this.publisher = publisher;
    }

    public RegistrationResult handle(RegisterCommand command) {
        Attendee attendee;
        try {
            attendee = new Attendee(command.attendeeId(), new Phone(command.phone()));
        } catch (IllegalArgumentException e) {
            Trace.step("application", "命令转换失败：" + e.getMessage());
            throw new UseCaseFailure.InvalidInput(e.getMessage());
        }
        try {
            return transactions.inTransaction(() -> register(new SessionId(command.sessionId()), attendee));
        } catch (RegistrationRefused e) {
            throw new UseCaseFailure.Rejected(e.getMessage());
        } catch (SessionRepository.ConcurrentModification e) {
            throw new UseCaseFailure.Conflict();
        }
    }

    private RegistrationResult register(SessionId sessionId, Attendee attendee) {
        {
            Trace.step("application", "开始用例 RegisterForSession");
            Session session = sessions.find(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("场次不存在"));
            session.register(attendee);
            Trace.step("domain", "Session.register 通过容量与重复检查");
            sessions.save(session);
            List<DomainEvent> events = session.drainEvents();
            transactions.afterCommit(() -> events.forEach(publisher::publish));
            return RegistrationResult.from(events);
        }
    }
}
