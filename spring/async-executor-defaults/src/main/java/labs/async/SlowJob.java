package labs.async;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 一个会卡住的 @Async 方法，用来观察线程数、队列和执行线程的类型。 */
@Service
public class SlowJob {
    // 注入的是 CGLIB 代理，字段要通过方法读取，直接读代理对象的字段拿不到目标对象的值
    private volatile String lastThread;
    private final AtomicInteger running = new AtomicInteger();

    @Async
    public void run(CountDownLatch release) throws InterruptedException {
        lastThread = Thread.currentThread().isVirtual() ? "virtual" : "platform";
        running.incrementAndGet();
        try {
            release.await();
        } finally {
            running.decrementAndGet();
        }
    }

    public int running() {
        return running.get();
    }

    public String lastThread() {
        return lastThread;
    }
}
