package labs.txattr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** MySQL 8.4.11（固定 digest）+ Connector/J 默认配置。 */
@SpringBootTest
class MySqlTxAttrTest {
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName
            .parse("mysql:8.4.11@sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a")
            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("labs").withUsername("labs").withPassword("example_password");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    TxAttrService service;
    @Autowired
    ItemRepository items;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM item");
        items.save(new Item(1L, "原始名字"));
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + "：" + t.getMessage();
    }

    @Test
    void readOnlyJdbcInsert() {
        String result;
        try {
            service.insertInReadOnly(2);
            result = "成功";
        } catch (RuntimeException e) {
            result = e.getClass().getSimpleName() + "，根因 " + rootMessage(e);
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM item WHERE id = 2", Integer.class);
        Facts.record("mysql.readonly.jdbc", "MySQL：readOnly 事务里用 JdbcTemplate 插入：" + result + "；表里 id=2 的行数 " + rows);
        assertThat(rows).isZero();
    }

    @Test
    void readOnlyDirtyChecking() {
        service.renameManagedEntityInReadOnly(1);
        String afterReadOnly = items.findById(1L).orElseThrow().getName();
        String save;
        try {
            service.explicitSaveInReadOnly(1);
            save = "没有报错";
        } catch (RuntimeException e) {
            save = e.getClass().getSimpleName();
        }
        String afterSave = items.findById(1L).orElseThrow().getName();
        service.renameManagedEntity(1);
        String afterReadWrite = items.findById(1L).orElseThrow().getName();
        Facts.record("mysql.readonly.jpa", "MySQL：readOnly 事务里修改已加载的实体：没有报错，数据库里仍是「" + afterReadOnly + "」；再显式调用 save：" + save
                + "，仍是「" + afterSave + "」；读写事务里同样的修改：变成「" + afterReadWrite + "」");
        assertThat(afterReadOnly).isEqualTo("原始名字");
        assertThat(afterReadWrite).isEqualTo("读写事务改的名字");
    }

    @Test
    void timeoutAfterLastStatement() throws Exception {
        long t0 = System.nanoTime();
        service.workAfterLastStatement(10);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM item WHERE id = 10", Integer.class);
        Facts.record("mysql.timeout.after", "timeout = 1 秒：插入后在 Java 里再耗时 1.5 秒：方法正常返回（耗时超过 1 秒=" + (ms > 1000) + "），提交了 " + rows + " 行");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void timeoutBeforeStatement() {
        String result;
        try {
            service.workBeforeStatement(11);
            result = "成功";
        } catch (Exception e) {
            result = e.getClass().getSimpleName();
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM item WHERE id = 11", Integer.class);
        Facts.record("mysql.timeout.before", "timeout = 1 秒：先在 Java 里耗时 1.5 秒再执行插入：" + result + "（在创建语句时检查剩余时间），提交了 " + rows + " 行");
        assertThat(result).isEqualTo("TransactionTimedOutException");
        assertThat(rows).isZero();
    }

    @Test
    void timeoutLongStatement() {
        long t0 = System.nanoTime();
        String result;
        try {
            service.longStatement();
            result = "成功";
        } catch (RuntimeException e) {
            result = e.getClass().getSimpleName() + "（" + rootMessage(e).replaceAll("：.*", "") + "）";
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Facts.record("mysql.timeout.statement", "timeout = 1 秒：一条 SELECT SLEEP(3)：" + result + "，在 1—2 秒之间结束=" + (ms >= 900 && ms < 2000)
                + "（剩余时间被设成了语句的查询超时）");
        assertThat(ms).isLessThan(2000);
    }
}
