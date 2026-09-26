package labs.aopboot;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;

/** 同一个方法上同时有 @Transactional 与 @Cacheable，两种切面顺序的差别。子类用配置项选择顺序。 */
abstract class OrderingBase {
    @Autowired ProfileService profiles;
    @Autowired CountingTransactionManager tm;
    @Autowired JdbcTemplate jdbc;

    abstract String label();

    @Test
    void cacheHitsAndTransactions() {
        profiles.name(1);
        int before = tm.begins.get();
        for (int i = 0; i < 10; i++) profiles.name(1);
        Facts.record("order." + label() + ".hits", label() + "：缓存命中 10 次，开启物理事务 " + (tm.begins.get() - before) + " 次");
    }

    @Test
    void rolledBackValueInCache() {
        assertThrows(UnexpectedRollbackException.class, () -> profiles.createIfAbsent(7));
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM profiles WHERE id = 7", Integer.class);
        String second;
        try {
            second = "返回「" + profiles.createIfAbsent(7) + "」";
        } catch (UnexpectedRollbackException e) {
            second = "再次抛出 UnexpectedRollbackException";
        }
        Facts.record("order." + label() + ".rollback", label() + "：第一次调用回滚，表里 id=7 的行数 " + rows + "；第二次调用" + second);
    }
}
