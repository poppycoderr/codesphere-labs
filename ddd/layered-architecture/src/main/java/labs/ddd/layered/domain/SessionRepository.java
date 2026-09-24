package labs.ddd.layered.domain;

import java.util.Optional;

/** 领域层声明的仓储端口：像集合一样存取场次聚合，不暴露表和 SQL。 */
public interface SessionRepository {

    Optional<Session> find(SessionId id);

    /** 按加载时的版本保存；版本已被别人推进时抛出 {@link ConcurrentModification}。 */
    void save(Session session);

    final class ConcurrentModification extends RuntimeException {
        public ConcurrentModification(SessionId id) {
            super("场次 " + id.value() + " 已被其他请求修改");
        }
    }
}
