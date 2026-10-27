package labs.events;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 同样的代码，连接池默认 autoCommit=false：清理连接时不需要切换 autoCommit。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:events-off;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.auto-commit=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true"})
class AutoCommitOffTest {
    @Autowired
    TicketService service;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void writesInAfterCommitListenerWithPoolAutoCommitOff() {
        service.auditPlain("plain-off");
        Integer plain = jdbc.queryForObject("SELECT COUNT(*) FROM audit WHERE note = 'plain-off'", Integer.class);
        service.auditRequiresNew("requires-new-off");
        Integer requiresNew = jdbc.queryForObject("SELECT COUNT(*) FROM audit WHERE note = 'requires-new-off'", Integer.class);
        Facts.record("after-commit.pool-off", "连接池 auto-commit=false 时同样的两种写法：不开新事务查到 " + plain + " 行，REQUIRES_NEW 查到 " + requiresNew + " 行");
    }
}
