package labs.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 审计与账户操作：REQUIRES_NEW 总是需要第二个连接，并且是一个独立的数据库事务。 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String msg) {
        jdbc.update("INSERT INTO audit_log (msg) VALUES (?)", msg);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void adjustBalance(long accountId, int delta) {
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", delta, accountId);
    }
}
