package labs.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskRejectedException;

class EventDeliveryTest {
    AnnotationConfigApplicationContext ctx;
    RegistrationService service;
    RegistrationStore store;
    Listeners listeners;

    @BeforeEach
    void start() {
        ctx = new AnnotationConfigApplicationContext(EventConfig.class);
        service = ctx.getBean(RegistrationService.class);
        store = ctx.getBean(RegistrationStore.class);
        listeners = ctx.getBean(Listeners.class);
        EventConfig.ASYNC_ERRORS.set(0);
    }

    @AfterEach
    void stop() {
        ctx.close();
    }

    @Test
    void syncListenersRunInPublisherThreadInOrder() throws Exception {
        listeners.expectAsync(1, 0);
        service.register("r1", "none");
        listeners.awaitAsync();
        String me = Thread.currentThread().getName();
        List<String> calls = listeners.calls();
        assertEquals("points@" + me, calls.get(0));
        assertEquals("audit@" + me, calls.get(1));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("email@async-")));
        assertTrue(calls.contains("afterCommit@" + me));
        assertEquals(List.of("r1"), store.committed());
        Facts.record("sync.order_and_threads", String.join(" ", calls).replace(me, "publisher"));
    }

    @Test
    void syncListenerFailureRollsBackThePublisher() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.register("r2", "audit"));
        assertEquals(List.of(), store.committed());
        assertTrue(listeners.calls().stream().noneMatch(c -> c.startsWith("afterCommit")));
        Facts.record("sync.failure", "发布者收到 " + e.getMessage() + "；报名已提交 " + store.committed().size() + " 条；提交后监听器调用 0 次");
    }

    @Test
    void asyncListenerFailureDoesNotReachThePublisher() throws Exception {
        listeners.expectAsync(1, 300);
        long t0 = System.nanoTime();
        service.register("r3", "email");
        long returnedMs = (System.nanoTime() - t0) / 1_000_000;
        listeners.awaitAsync();
        Thread.sleep(50);
        assertEquals(List.of("r3"), store.committed());
        assertEquals(1, EventConfig.ASYNC_ERRORS.get());
        Facts.record("async.failure", "发布者 " + (returnedMs < 300 ? "在监听器完成前返回" : "等待了监听器") + "，报名已提交；异步异常只进入 AsyncUncaughtExceptionHandler（" + EventConfig.ASYNC_ERRORS.get() + " 次）");
    }

    @Test
    void boundedExecutorRejectsAtThePublisher() throws Exception {
        listeners.expectAsync(3, 500);
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            try {
                service.registerWithoutTransaction("q" + i);
                accepted++;
            } catch (TaskRejectedException e) {
                rejected++;
            }
        }
        listeners.awaitAsync();
        assertEquals(3, accepted);
        assertEquals(7, rejected);
        Facts.record("async.rejection", "线程 1、队列 2：连续发布 10 个事件，" + accepted + " 个被接受，" + rejected + " 个在发布者线程抛出 TaskRejectedException；被拒绝的报名已经保存 " + store.committed().size() + " 条");
    }

    @Test
    void fieldsOfTheProxyAreNotTheBeansFields() throws Exception {
        java.lang.reflect.Field f = Listeners.class.getDeclaredField("calls");
        f.setAccessible(true);
        Object onProxy = f.get(listeners);
        Facts.record("proxy.field", "容器里的 Listeners 是 " + (listeners.getClass().getName().contains("$$SpringCGLIB$$") ? "CGLIB 代理" : "原始对象")
                + "，代理对象上的 calls 字段为 " + onProxy);
        assertEquals(null, onProxy);
    }

    @Test
    void afterCommitListenerIsSkippedWithoutTransaction() {
        service.registerWithoutTransaction("r4");
        assertTrue(listeners.calls().stream().noneMatch(c -> c.startsWith("afterCommit")));
        Facts.record("after_commit.no_transaction", "没有事务时发布：提交后监听器调用 0 次，报名已保存 " + store.committed().size() + " 条");
    }

    @Test
    void afterCommitFailureHappensAfterTheCommit() {
        Throwable thrown = null;
        try {
            service.register("r5", "afterCommit");
        } catch (Throwable t) {
            thrown = t;
        }
        assertEquals(List.of("r5"), store.committed());
        Facts.record("after_commit.failure", "提交后监听器抛异常：报名已提交 " + store.committed().size() + " 条；发布者"
                + (thrown == null ? "没有收到异常" : "收到 " + thrown.getClass().getSimpleName()));
    }
}
