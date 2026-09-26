package labs.async;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 默认配置：applicationTaskExecutor 是 ThreadPoolTaskExecutor，队列无界，线程数停在核心线程数。 */
@SpringBootTest
class DefaultExecutorTest {
    @Autowired ThreadPoolTaskExecutor applicationTaskExecutor;
    @Autowired SlowJob job;

    @Test
    void unboundedQueueKeepsPoolAtCoreSize() throws Exception {
        var pool = applicationTaskExecutor.getThreadPoolExecutor();
        Facts.record("default.config", "applicationTaskExecutor=" + applicationTaskExecutor.getClass().getSimpleName()
                + "，corePoolSize=" + pool.getCorePoolSize() + "，maxPoolSize=" + pool.getMaximumPoolSize()
                + "，队列剩余容量=" + pool.getQueue().remainingCapacity());
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 50; i++) job.run(release);
        TimeUnit.MILLISECONDS.sleep(300);
        Facts.record("default.load", "提交 50 个卡住的 @Async 任务：线程数 " + pool.getPoolSize() + "，排队 " + pool.getQueue().size()
                + "，同时在运行的任务 " + job.running() + " 个，执行线程 " + job.lastThread());
        assertEquals(8, pool.getPoolSize());
        assertEquals(42, pool.getQueue().size());
        release.countDown();
    }
}
