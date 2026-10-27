package labs.txattr;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 同样的 readOnly 代码换成 H2。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:txattr;DB_CLOSE_DELAY=-1")
class H2ReadOnlyTest {
    @Autowired
    TxAttrService service;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void readOnlyJdbcInsertOnH2() {
        String result;
        try {
            service.insertInReadOnly(2);
            result = "成功";
        } catch (RuntimeException e) {
            result = e.getClass().getSimpleName();
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM item WHERE id = 2", Integer.class);
        Facts.record("h2.readonly.jdbc", "H2：同样在 readOnly 事务里用 JdbcTemplate 插入：" + result + "；表里 id=2 的行数 " + rows);
    }
}
