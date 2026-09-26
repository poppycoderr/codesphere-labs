package labs.tx;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 所有测试类共用一个 MySQL 8.4.11 容器（固定 digest），每个测试前清空数据。 */
abstract class MySqlBase {
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName
            .parse("mysql:8.4.11@sha256:85b9bf2e29cf836ecb8c2a15a935d4ba0c606631dff1dd79531a11983c638f2a")
            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("labs")
            .withUsername("labs")
            .withPassword("example_password");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM points");
        jdbc.update("DELETE FROM import_rows");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("UPDATE accounts SET balance = 100 WHERE id = 1");
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
