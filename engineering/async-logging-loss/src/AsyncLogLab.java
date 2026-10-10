import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;

/**
 * 异步日志在哪里丢：Logback 的 AsyncAppender 在队列快满时主动丢弃 INFO 及以下级别、队列满时阻塞业务线程或丢弃一切、
 * 关闭时只等有限的时间、进程直接退出时不等。下游用一个可以被卡住、也可以设定每条耗时的 appender 代替磁盘或网络。
 * 子进程模式 exit-*：记 200 条日志（下游每条 5 毫秒）后以三种方式结束进程，由父进程数实际写出的条数。
 */
public class AsyncLogLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    /** 代替「写磁盘 / 发网络」：可以卡住，也可以每条固定耗时；记录各级别实际写出的条数 */
    static final class SlowSink extends AppenderBase<ILoggingEvent> {
        final AtomicInteger info = new AtomicInteger(), warn = new AtomicInteger(), error = new AtomicInteger();
        final CountDownLatch firstTaken = new CountDownLatch(1);
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile long millisPerEvent;
        volatile boolean print;
        volatile String lastCaller = "";
        @Override protected void append(ILoggingEvent e) {
            firstTaken.countDown();
            // 模拟不可中断的写入：AsyncAppender 关闭时会中断工作线程，这里不因为中断而放弃这条日志
            while (gate.getCount() > 0) { try { gate.await(); } catch (InterruptedException ignored) { } }
            if (millisPerEvent > 0) { long until = System.nanoTime() + millisPerEvent * 1_000_000; while (System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(200_000); }
            (e.getLevel() == Level.ERROR ? error : e.getLevel() == Level.WARN ? warn : info).incrementAndGet();
            lastCaller = e.hasCallerData() && e.getCallerData().length > 0 ? e.getCallerData()[0].getMethodName() : "?";
            if (print) System.out.println("WRITTEN " + e.getFormattedMessage());
        }
        String counts() { return "INFO " + info.get() + "，WARN " + warn.get() + "，ERROR " + error.get(); }
    }

    record Setup(LoggerContext context, Logger log, AsyncAppender async, SlowSink sink) { }

    static Setup setup(java.util.function.Consumer<AsyncAppender> tune) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        context.start();                                                // 上一个场景 stop() 过；不重新 start，下一次 stop() 不会再停 appender
        SlowSink sink = new SlowSink(); sink.setContext(context); sink.start();
        AsyncAppender async = new AsyncAppender(); async.setContext(context); async.addAppender(sink);
        tune.accept(async); async.start();
        Logger log = context.getLogger("app"); log.setLevel(Level.INFO); log.setAdditive(false); log.addAppender(async);
        return new Setup(context, log, async, sink);
    }

    /** 在另一个线程里记日志，300 毫秒后看它返回了没有 */
    static Thread inBackground(Runnable logCall) throws Exception {
        Thread t = new Thread(logCall); t.setDaemon(true); t.start(); t.join(300);
        return t;
    }
    /** 放开下游，等后台线程返回、队列排空，再关闭日志系统 */
    static void drainAndStop(Setup s, Thread pending) throws Exception {
        s.sink.gate.countDown();
        if (pending != null) pending.join();
        while (s.async.getNumberOfElementsInQueue() > 0) Thread.sleep(10);
        Thread.sleep(100);
        s.context.stop();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { exitChild(args[0]); return; }
        Setup d = setup(a -> { });
        out("env", "java.version=" + System.getProperty("java.version") + " logback=" + ch.qos.logback.core.CoreConstants.class.getPackage().getImplementationVersion());
        out("defaults", "队列长度 " + d.async.getQueueSize() + "，丢弃阈值 " + d.async.getDiscardingThreshold() + "（剩余容量低于它时丢弃 INFO 及以下），neverBlock = " + d.async.isNeverBlock()
                + "，关闭时最多等 " + d.async.getMaxFlushTime() + " ms，记录调用位置 = " + d.async.isIncludeCallerData());

        // 一、下游卡住时：INFO 被悄悄丢弃
        d.sink.gate = new CountDownLatch(1);
        d.log.info("first"); d.sink.firstTaken.await();                 // 工作线程取走第一条并卡在下游
        for (int i = 0; i < 1000; i++) d.log.info("info {}", i);
        out("discard.info", "下游卡住期间记了 1001 条 INFO，全部立即返回；此时队列里有 " + d.async.getNumberOfElementsInQueue() + " 条，剩余容量 " + d.async.getRemainingCapacity());
        int room = d.async.getRemainingCapacity();
        for (int i = 0; i < room; i++) d.log.warn("warn {}", i);
        out("discard.warn_fills", "再记 " + room + " 条 WARN（不在丢弃之列）：队列里 " + d.async.getNumberOfElementsInQueue() + " 条，剩余容量 " + d.async.getRemainingCapacity());
        Thread blocked = inBackground(() -> d.log.error("payment failed"));
        out("block.error", "队列满了之后再记一条 ERROR：业务线程 300 ms 内返回 = " + !blocked.isAlive());
        drainAndStop(d, blocked);
        out("discard.written", "下游恢复并正常关闭后，实际写出的：" + d.sink.counts() + "（记了 INFO 1001、WARN " + room + "、ERROR 1）");

        // 二、neverBlock = true：不阻塞，改为丢弃
        Setup n = setup(a -> a.setNeverBlock(true));
        n.sink.gate = new CountDownLatch(1);
        n.log.info("first"); n.sink.firstTaken.await();
        for (int i = 0; i < 300; i++) n.log.warn("warn {}", i);
        Thread errors = inBackground(() -> { for (int i = 0; i < 50; i++) n.log.error("payment failed {}", i); });
        boolean prompt = !errors.isAlive();
        drainAndStop(n, errors);
        out("never_block", "neverBlock = true，下游卡住期间记 300 条 WARN 再记 50 条 ERROR：业务线程立即返回 = " + prompt + "；实际写出 " + n.sink.counts());

        // 三、discardingThreshold = 0：不主动丢 INFO，代价是 INFO 也会阻塞
        Setup z = setup(a -> a.setDiscardingThreshold(0));
        z.sink.gate = new CountDownLatch(1);
        z.log.info("first"); z.sink.firstTaken.await();
        for (int i = 0; i < 256; i++) z.log.info("info {}", i);
        Thread oneMore = inBackground(() -> z.log.info("one more"));
        boolean infoPrompt = !oneMore.isAlive();
        drainAndStop(z, oneMore);
        out("no_discard", "discardingThreshold = 0，下游卡住期间记满队列后再记一条 INFO：业务线程 300 ms 内返回 = " + infoPrompt + "；实际写出 " + z.sink.counts());

        // 四、关闭时只等 maxFlushTime
        Setup s = setup(a -> { a.setDiscardingThreshold(0); a.setQueueSize(5000); });
        s.sink.millisPerEvent = 2;
        for (int i = 0; i < 3000; i++) s.log.info("info {}", i);
        long t = System.nanoTime();
        s.context.stop();
        long stopMs = (System.nanoTime() - t) / 1_000_000;
        int writtenAtStop = s.sink.info.get();
        out("flush.default", "队列里有 3000 条、下游每条 2 ms（写完要 6 秒）时关闭日志系统：stop() 在 0.9–1.5 秒内返回 = " + (stopMs >= 900 && stopMs <= 1500) + "，此时写出的不到一半 = " + (writtenAtStop < 1500));
        System.err.println("timing\tflush.default\tstop " + stopMs + " ms, written " + writtenAtStop);
        Setup s2 = setup(a -> { a.setDiscardingThreshold(0); a.setQueueSize(5000); a.setMaxFlushTime(0); });
        s2.sink.millisPerEvent = 2;
        for (int i = 0; i < 500; i++) s2.log.info("info {}", i);
        s2.context.stop();
        out("flush.unbounded", "maxFlushTime = 0（一直等到写完），500 条：写出 " + s2.sink.info.get());

        // 五、调用位置
        Setup c = setup(a -> { });
        c.log.info("where"); drainAndStop(c, null);
        Setup c2 = setup(a -> a.setIncludeCallerData(true));
        c2.log.info("where"); drainAndStop(c2, null);
        out("caller", "默认配置下，下游拿到的调用方法名 = " + c.sink.lastCaller + "；includeCallerData = true 时 = " + c2.sink.lastCaller);

        // 六、进程怎么结束
        for (String mode : new String[]{"exit-return", "exit-system-exit", "exit-stop-first"}) {
            Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"), "src/AsyncLogLab.java", mode)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long written = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("WRITTEN")).count();
            p.waitFor(30, TimeUnit.SECONDS);
            String how = mode.equals("exit-return") ? "main 方法直接返回" : mode.equals("exit-system-exit") ? "调用 System.exit(0)" : "退出前先调用 LoggerContext.stop()";
            System.err.println("timing\t" + mode + "\twritten " + written);
            out(mode.replace('-', '_'), "记 200 条日志（下游每条 5 ms）后" + how + "：" + (written == 200 ? "200 条全部写出" : "写出的不足 50 条 = " + (written < 50)));
        }
    }

    static void exitChild(String mode) {
        Setup s = setup(a -> a.setDiscardingThreshold(0));
        s.sink.millisPerEvent = 5; s.sink.print = true;
        for (int i = 0; i < 200; i++) s.log.info("order {}", i);
        if (mode.equals("exit-system-exit")) System.exit(0);
        if (mode.equals("exit-stop-first")) { s.async.setMaxFlushTime(0); s.context.stop(); }
    }
}
