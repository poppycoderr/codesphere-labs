package labs.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 四类订阅者：两个有序的同步监听器、一个异步监听器、一个提交后监听器。每次调用记录「监听器@线程」。
 * 因为有 @Async 方法，容器暴露的是 CGLIB 代理：外部只能通过方法访问状态，直接读写字段访问到的是代理对象自己的空字段。
 */
public class Listeners {
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch asyncDone = new CountDownLatch(0);
    private volatile long asyncSleepMillis;

    public List<String> calls() {
        return calls;
    }

    public void expectAsync(int count, long sleepMillis) {
        asyncDone = new CountDownLatch(count);
        asyncSleepMillis = sleepMillis;
    }

    public boolean awaitAsync() throws InterruptedException {
        return asyncDone.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @EventListener
    @Order(2)
    public void audit(Events e) {
        calls.add("audit@" + Thread.currentThread().getName());
        if ("audit".equals(e.failIn())) throw new IllegalStateException("审计写入失败");
    }

    @EventListener
    @Order(1)
    public void points(Events e) {
        calls.add("points@" + Thread.currentThread().getName());
    }

    @Async
    @EventListener
    public void email(Events e) throws InterruptedException {
        try {
            Thread.sleep(asyncSleepMillis);
            calls.add("email@" + Thread.currentThread().getName());
            if ("email".equals(e.failIn())) throw new IllegalStateException("邮件服务不可用");
        } finally {
            asyncDone.countDown();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyOrganizer(Events e) {
        calls.add("afterCommit@" + Thread.currentThread().getName());
        if ("afterCommit".equals(e.failIn())) throw new IllegalStateException("通知组织者失败");
    }
}
