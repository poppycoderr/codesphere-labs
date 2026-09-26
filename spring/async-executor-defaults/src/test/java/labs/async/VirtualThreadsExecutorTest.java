package labs.async;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/** 开启 spring.threads.virtual.enabled 后，applicationTaskExecutor 换成 SimpleAsyncTaskExecutor，每个任务一个虚拟线程。 */
@SpringBootTest(properties = "spring.threads.virtual.enabled=true")
class VirtualThreadsExecutorTest {
    @Autowired @Qualifier("applicationTaskExecutor") Executor applicationTaskExecutor;
    @Autowired SlowJob job;

    @Test
    void virtualThreadsSwitchExecutor() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 50; i++) job.run(release);
        TimeUnit.MILLISECONDS.sleep(300);
        Facts.record("virtual.config", "spring.threads.virtual.enabled=true：applicationTaskExecutor="
                + applicationTaskExecutor.getClass().getSimpleName() + "，@Async 方法运行在 " + job.lastThread() + " 线程上，同时在运行的任务 " + job.running() + " 个");
        assertEquals(50, job.running());
        assertEquals("virtual", job.lastThread());
        release.countDown();
    }
}
