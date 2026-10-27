package labs.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 提交后监听器里写数据库：不开新事务与 REQUIRES_NEW 两种写法。 */
@Component
public class AuditListeners {
    private final JdbcTemplate jdbc;

    AuditListeners(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener
    void plain(Events.AuditPlain e) {
        jdbc.update("INSERT INTO audit(note) VALUES (?)", e.note());
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void requiresNew(Events.AuditRequiresNew e) {
        jdbc.update("INSERT INTO audit(note) VALUES (?)", e.note());
    }
}
