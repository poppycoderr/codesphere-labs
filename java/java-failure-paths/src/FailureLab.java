import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * 出错路径上的信息是怎么丢的：finally 里的 return 与异常、关闭失败、换一个异常重新抛出、复用的异常对象、
 * 热点代码里被省掉调用栈的异常、线程池与 CompletableFuture 里没有人看的异常、没有在 finally 里释放的锁。
 * 「fastthrow」参数是给子进程用的：同一段代码分别在默认参数和 -XX:-OmitStackTraceInFastThrow 下运行。
 */
public class FailureLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static String brief(Throwable t) { return t == null ? "null" : t.getClass().getSimpleName() + "（" + t.getMessage() + "）"; }

    static final class Res implements AutoCloseable {
        final String name; final List<String> log; final boolean failOnClose;
        Res(String name, List<String> log, boolean failOnOpen, boolean failOnClose) throws IOException {
            if (failOnOpen) throw new IOException("打开" + name + "失败");
            this.name = name; this.log = log; this.failOnClose = failOnClose; log.add("打开" + name);
        }
        @Override public void close() throws IOException { log.add("关闭" + name); if (failOnClose) throw new IOException("关闭" + name + "失败"); }
    }

    @SuppressWarnings("finally")
    static int finallyOverridesReturn() { try { return 1; } finally { return 2; } }
    @SuppressWarnings("finally")
    static int finallySwallows() { try { throw new IllegalStateException("扣款失败"); } finally { return -1; } }

    static final RuntimeException SHARED = new RuntimeException("余额不足");
    static void pay() { throw SHARED; }
    static void refund() { throw SHARED; }
    static void originate() { throw new IllegalStateException("库存服务返回 503"); }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("fastthrow")) { fastThrow(); return; }
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、finally 里的 return
        out("finally.return_value", "try { return 1; } finally { return 2; } 返回 " + finallyOverridesReturn());
        out("finally.swallow", "try { throw …; } finally { return -1; }：方法正常返回 " + finallySwallows() + "，调用方看不到异常");
        out("finally.javac_default", "javac 默认参数：" + compile("class T { int f() { try { return 1; } finally { return 2; } } }"));
        out("finally.javac_xlint", "javac -Xlint:finally：" + compile("class T { int f() { try { return 1; } finally { return 2; } } }", "-Xlint:finally"));

        // 二、关闭失败盖住了原来的异常
        List<String> log = new ArrayList<>();
        try {
            Res r = new Res("连接", log, false, true);
            try { throw new IllegalStateException("写入失败"); } finally { r.close(); }
        } catch (Exception e) { out("close.finally", "手写 finally 关闭：调用方收到 " + brief(e) + "，被压住的异常 " + e.getSuppressed().length + " 个，原来的「写入失败」不见了"); }
        try (Res r = new Res("连接", log, false, true)) {
            throw new IllegalStateException("写入失败");
        } catch (Exception e) { out("close.twr", "try-with-resources：调用方收到 " + brief(e) + "，getSuppressed() = [" + brief(e.getSuppressed()[0]) + "]"); }
        log.clear();
        try (Res a = new Res("A", log, false, false); Res b = new Res("B", log, false, false); Res c = new Res("C", log, true, false)) {
            log.add("执行主体");
        } catch (Exception e) { out("close.order", "三个资源、第三个打开失败：" + String.join(" → ", log) + "；收到 " + brief(e)); }
        log.clear();
        try {
            Res a = new Res("A", log, false, false); Res b = new Res("B", log, true, false);
            try { log.add("执行主体"); } finally { b.close(); a.close(); }
        } catch (Exception e) { out("close.manual_leak", "手写：两个资源先都打开再进 try，第二个打开失败：" + String.join(" → ", log) + "（A 没有被关闭）"); }

        // 三、换一个异常抛出去
        try { try { originate(); } catch (Exception e) { throw new RuntimeException("下单失败：" + e.getMessage()); } }
        catch (RuntimeException e) { out("wrap.no_cause", "throw new RuntimeException(\"…\" + e.getMessage())：getCause() = " + e.getCause() + "，调用栈第一帧在 " + e.getStackTrace()[0].getMethodName() + "，找不到 originate"); }
        try { try { originate(); } catch (Exception e) { throw new RuntimeException("下单失败", e); } }
        catch (RuntimeException e) { out("wrap.with_cause", "throw new RuntimeException(\"…\", e)：getCause() = " + brief(e.getCause()) + "，原因的第一帧在 " + e.getCause().getStackTrace()[0].getMethodName()); }
        Exception noMessage = new IllegalStateException();
        out("wrap.null_message", "\"失败：\" + e.getMessage() 遇到没有消息的异常 = 失败：" + noMessage.getMessage() + "；\"失败：\" + e = 失败：" + noMessage);
        try { Object o = null; o.toString(); } catch (NullPointerException e) { out("wrap.npe_message", "空指针异常的消息：" + e.getMessage()); }

        // 四、复用同一个异常对象
        String payFrame = null, refundFrame = null;
        try { pay(); } catch (RuntimeException e) { payFrame = e.getStackTrace()[0].getMethodName(); }
        try { refund(); } catch (RuntimeException e) { refundFrame = e.getStackTrace()[0].getMethodName(); }
        out("shared.static", "static final 的异常对象从 pay() 抛出时第一帧是 " + payFrame + "，从 refund() 抛出时第一帧是 " + refundFrame);
        RuntimeException stackless = new RuntimeException("余额不足", null, false, false) { };
        out("shared.stackless", "关掉调用栈的异常（writableStackTrace = false）：getStackTrace().length = " + stackless.getStackTrace().length);

        // 五、热点代码里的隐式异常
        out("fastthrow.default", child());
        out("fastthrow.disabled", child("-XX:-OmitStackTraceInFastThrow"));

        // 六、线程池
        List<String> uncaught = new ArrayList<>();
        CountDownLatch handled = new CountDownLatch(1);
        ThreadFactory tf = r -> { Thread t = new Thread(r); t.setUncaughtExceptionHandler((th, e) -> { synchronized (uncaught) { uncaught.add(brief(e)); } handled.countDown(); }); return t; };
        ExecutorService pool = Executors.newFixedThreadPool(1, tf);
        Future<?> f = pool.submit(() -> { throw new IllegalStateException("对账失败"); });
        pool.execute(() -> { throw new IllegalStateException("推送失败"); });
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS);
        handled.await(5, TimeUnit.SECONDS);                               // 处理器在工作线程退出时才被调用，可能晚于线程池终止
        Thread.sleep(100);
        synchronized (uncaught) { out("pool.handler", "submit 与 execute 各提交一个抛异常的任务，未捕获异常处理器收到：" + uncaught); }
        try { f.get(); } catch (ExecutionException e) { out("pool.submit", "submit 的异常只在 Future.get() 时出现：" + brief(e.getCause()) + "；没有人调用 get 就没有任何输出"); }

        ScheduledExecutorService ses = Executors.newScheduledThreadPool(1, tf);
        AtomicInteger runs = new AtomicInteger(); CountDownLatch third = new CountDownLatch(1);
        Future<?> periodic = ses.scheduleAtFixedRate(() -> { if (runs.incrementAndGet() == 3) { third.countDown(); throw new IllegalStateException("第 3 次执行失败"); } }, 0, 20, TimeUnit.MILLISECONDS);
        third.await(); Thread.sleep(400);
        out("pool.scheduled", "scheduleAtFixedRate 每 20 ms 一次，第 3 次抛异常：又过了 400 ms 后一共执行了 " + runs.get() + " 次；isDone = " + periodic.isDone() + "；未捕获异常处理器收到的仍是 " + uncaught.size() + " 条");
        ses.shutdownNow();

        // 七、CompletableFuture
        CompletableFuture<String> direct = new CompletableFuture<>();
        direct.completeExceptionally(new IllegalStateException("超时"));
        out("cf.direct", "completeExceptionally(ISE) 之后 exceptionally 收到 " + direct.exceptionally(e -> e.getClass().getSimpleName()).join());
        CompletableFuture<String> async = CompletableFuture.supplyAsync(() -> { throw new IllegalStateException("超时"); });
        out("cf.async", "supplyAsync 里抛 ISE，exceptionally 收到 " + async.exceptionally(e -> e.getClass().getSimpleName() + "，原因是 " + e.getCause().getClass().getSimpleName()).join());
        out("cf.dependent", "completeExceptionally(ISE) 之后再 thenApply，下一级的 exceptionally 收到 " + direct.thenApply(x -> x).exceptionally(e -> e.getClass().getSimpleName()).join());
        try { async.join(); } catch (CompletionException e) { out("cf.join", "join() 抛 " + e.getClass().getSimpleName() + "，原因 " + brief(e.getCause())); }
        try { async.get(); } catch (ExecutionException e) { out("cf.get", "get() 抛 " + e.getClass().getSimpleName() + "，原因 " + brief(e.getCause())); }
        CompletableFuture<Void> nobody = CompletableFuture.runAsync(() -> { throw new IllegalStateException("没人看的失败"); });
        Thread.sleep(100);
        out("cf.unobserved", "没有任何后续处理的失败任务：isCompletedExceptionally = " + nobody.isCompletedExceptionally() + "，标准错误与未捕获异常处理器都没有输出");

        // 八、锁没有在 finally 里释放
        ReentrantLock lock = new ReentrantLock();
        Thread holder = new Thread(() -> { lock.lock(); if (lock.isLocked()) throw new IllegalStateException("处理失败"); lock.unlock(); });
        holder.setUncaughtExceptionHandler((t, e) -> { });
        holder.start(); holder.join();
        out("lock.leaked", "lock(); 处理; unlock(); 处理中途抛异常，线程已经结束：isLocked = " + lock.isLocked() + "，另一个线程 tryLock(200 ms) = " + lock.tryLock(200, TimeUnit.MILLISECONDS));
    }

    /** 让同一处隐式空指针异常反复抛出，直到出现没有调用栈的那一个 */
    static void fastThrow() {
        Object[] holder = new Object[1];
        int firstEmpty = -1; String message = null; NullPointerException first = null, later = null;
        for (int i = 0; i < 200_000; i++) {
            try { deref(holder); } catch (NullPointerException e) {
                if (e.getStackTrace().length == 0) { if (first == null) { first = e; firstEmpty = i; message = e.getMessage(); } else later = e; }
            }
        }
        System.out.println(firstEmpty < 0 ? "20 万次里每一次都带调用栈"
                : "20 万次里出现了调用栈为空的异常，消息 = " + message + "，后面的异常与它是同一个对象 = " + (first == later));
    }
    static int deref(Object[] holder) { return holder[0].hashCode(); }

    static String child(String... jvmArgs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java")));
        cmd.addAll(List.of(jvmArgs)); cmd.addAll(List.of("src/FailureLab.java", "fastthrow"));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String s = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        p.waitFor();
        return (jvmArgs.length == 0 ? "默认参数：" : String.join(" ", jvmArgs) + "：") + s;
    }

    static String compile(String source, String... options) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///T.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        List<String> opts = new ArrayList<>(List.of("-proc:none", "-d", System.getProperty("java.io.tmpdir")));
        opts.addAll(List.of(options));
        ToolProvider.getSystemJavaCompiler().getTask(null, null, diagnostics, opts, null, List.of(file)).call();
        return diagnostics.getDiagnostics().stream().map(d -> d.getKind() + " " + d.getMessage(Locale.ROOT)).findFirst().orElse("没有任何提示");
    }
}
