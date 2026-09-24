package labs.ddd.layered.infrastructure;

import java.util.Optional;
import labs.ddd.layered.domain.Session;
import labs.ddd.layered.domain.SessionId;
import labs.ddd.layered.domain.SessionRepository;
import labs.ddd.layered.observability.Trace;

/** 仓储实现：版本号对应 UPDATE ... WHERE version = ? 的乐观锁。 */
public final class SessionRepositoryImpl implements SessionRepository {

    private final InMemoryDatabase db;
    private final SessionConverter converter;

    public SessionRepositoryImpl(InMemoryDatabase db, SessionConverter converter) {
        this.db = db;
        this.converter = converter;
    }

    @Override
    public Optional<Session> find(SessionId id) {
        Trace.step("infrastructure", "SELECT session " + id.value());
        return Optional.ofNullable(db.read(id.value())).map(converter::toDomain);
    }

    @Override
    public void save(Session session) {
        if (db.committedVersion(session.id().value()) != session.version()) {
            Trace.step("infrastructure", "UPDATE session ... WHERE version=" + session.version() + "：0 行");
            throw new ConcurrentModification(session.id());
        }
        SessionRecord next = converter.toRecord(session);
        db.write(new SessionRecord(next.id(), next.capacity(), next.version() + 1, next.confirmed(), next.waitlist()));
        Trace.step("infrastructure", "UPDATE session ... WHERE version=" + session.version() + "：1 行");
    }
}
