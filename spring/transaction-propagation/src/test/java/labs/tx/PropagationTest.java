package labs.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.UnexpectedRollbackException;

/** 默认配置（Spring Boot 4.1.1 自动配置的 JdbcTransactionManager）下的传播行为。 */
@SpringBootTest
class PropagationTest extends MySqlBase {
    @Autowired OrderService orders;
    @Autowired ImportService imports;
    @Autowired CheckoutService checkout;
    @Autowired AfterCommitListener listener;

    @Test
    void requiredInnerFailureMarksSharedTransactionRollbackOnly() {
        var e = assertThrows(UnexpectedRollbackException.class, () -> orders.placeOrderSwallowing(1));
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE id = 1"));
        Facts.record("required.swallow", "外层吞掉内层异常：抛出 " + e.getClass().getSimpleName() + "，订单行数 0");
    }

    @Test
    void noRollbackForLetsOuterCommit() {
        orders.placeOrderLenient(2);
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id = 2"));
        Facts.record("required.noRollbackFor", "内层 noRollbackFor：外层正常提交，订单行数 1");
    }

    @Test
    void nestedRollsBackToSavepointOnly() {
        Map<Long, String> rows = new LinkedHashMap<>();
        rows.put(1L, "a");
        rows.put(2L, " ");
        rows.put(3L, "c");
        List<Long> failures = imports.importBatch(rows, true);
        assertEquals(List.of(2L), failures);
        assertEquals(List.of(1L, 3L), jdbc.queryForList("SELECT id FROM import_rows ORDER BY id", Long.class));
        Facts.record("nested", "NESTED：第 2 行失败回滚到保存点，提交后留下 " + jdbc.queryForList("SELECT id FROM import_rows ORDER BY id", Long.class));

        jdbc.update("DELETE FROM import_rows");
        var e = assertThrows(UnexpectedRollbackException.class, () -> imports.importBatch(rows, false));
        assertEquals(0, count("SELECT COUNT(*) FROM import_rows"));
        Facts.record("nested.required", "同样的批次改用 REQUIRED：" + e.getClass().getSimpleName() + "，留下 0 行");
    }

    @Test
    void requiresNewCommitsIndependently() {
        assertThrows(IllegalStateException.class, () -> checkout.placeOrderWithAudit(3));
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE id = 3"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE msg = 'ORDER_CREATED 3'"));
        Facts.record("requiresNew.audit", "订单回滚后：订单行数 0，REQUIRES_NEW 写入的审计行数 1");
    }

    @Test
    void requiresNewStarvesWhenPoolIsFull() throws Exception {
        CyclicBarrier bothInside = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> results = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < 2; i++) {
            String msg = "starved-" + i;
            results.add(pool.submit(() -> {
                checkout.holdConnectionThenAudit(bothInside, msg);
                return null;
            }));
        }
        List<String> errors = new ArrayList<>();
        for (Future<?> f : results) {
            try {
                f.get();
                errors.add("成功");
            } catch (java.util.concurrent.ExecutionException e) {
                errors.add(e.getCause().getClass().getSimpleName() + " <- " + rootCause(e).getClass().getSimpleName());
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        assertTrue(errors.stream().allMatch(s -> s.startsWith("CannotCreateTransactionException")), errors.toString());
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log"));
        Facts.record("requiresNew.pool", "连接池 2 个连接、2 个外层事务各占 1 个：两个 REQUIRES_NEW 都失败 " + errors);
        Facts.record("requiresNew.pool.elapsed", ms >= 1000 ? "等待了连接超时（1 秒）之后才失败" : "未等待超时：" + ms + "ms");
    }

    @Test
    void requiresNewWaitsForOuterRowLock() {
        long t0 = System.nanoTime();
        var e = assertThrows(RuntimeException.class, () -> checkout.lockThenAdjustInNewTransaction(1));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 2000, "elapsed " + ms);
        assertEquals(100, count("SELECT balance FROM accounts WHERE id = 1"));
        Facts.record("requiresNew.lock", "外层 FOR UPDATE 后内层 REQUIRES_NEW 更新同一行：" + e.getClass().getSimpleName()
                + "，根因 " + rootCause(e).getMessage());
        Facts.record("requiresNew.lock.elapsed", "等满 innodb_lock_wait_timeout（2 秒）才失败，InnoDB 不把它当作死锁");
    }

    @Test
    void checkedExceptionCommitsByDefault() {
        assertThrows(CheckoutService.PaymentRejected.class, () -> checkout.confirmWithCheckedFailure(4));
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id = 4"));
        Facts.record("rollbackOn.default", "默认规则下抛出受检异常：事务提交，订单行数 1");
    }

    @Test
    void afterCommitListenerRunsOnlyOnCommit() {
        listener.received.clear();
        checkout.placeOrderAndPublish(5, false);
        assertThrows(IllegalStateException.class, () -> checkout.placeOrderAndPublish(6, true));
        assertEquals(List.of(5L), listener.received);
        Facts.record("afterCommit", "AFTER_COMMIT 监听器：提交的订单 5 收到，回滚的订单 6 没有收到");
    }

    static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
