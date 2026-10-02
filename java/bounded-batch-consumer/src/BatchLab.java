import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 队列 + 批量消费者的五个边界：批的大小、队列之外的在途量、按时间刷出、批内失败、关闭。
 * 用闩锁和预先装好的队列构造确定的场景，不依赖吞吐量。
 */
public class BatchLab {

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));
        batchSize();
        inFlight();
        flushByTime();
        failure();
        shutdown();
        poisonPill();
        System.exit(0);
    }

    /** 1. 「取到空为止」的批没有上限：积压多少，一批就有多大。 */
    static void batchSize() throws Exception {
        for (int max : new int[] {Integer.MAX_VALUE, 100}) {
            BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(1000);
            for (int i = 0; i < 1000; i++) queue.put(i);                 // 下游慢了一阵之后的积压
            List<Integer> sizes = new ArrayList<>();
            while (!queue.isEmpty()) {
                List<Integer> batch = new ArrayList<>();
                batch.add(queue.take());
                queue.drainTo(batch, max - 1);
                sizes.add(batch.size());
            }
            out("size." + (max == 100 ? "capped" : "unbounded"),
                    "队列积压 1000 条，" + (max == 100 ? "drainTo(batch, 99)" : "drainTo(batch)") + "：共 " + sizes.size() + " 批，最大一批 " + sizes.stream().max(Integer::compare).get() + " 条");
        }
    }

    /** 2. 队列有界不等于在途有界：消费者把批交给异步写入后立刻回来取下一批。 */
    static void inFlight() throws Exception {
        for (boolean bounded : new boolean[] {false, true}) {
            BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(100);
            ExecutorService writer = Executors.newSingleThreadExecutor();       // 默认无界队列
            CountDownLatch sinkBlocked = new CountDownLatch(1);                 // 下游卡住
            Semaphore window = new Semaphore(200);
            AtomicInteger handedOff = new AtomicInteger(), written = new AtomicInteger(), produced = new AtomicInteger();
            Thread consumer = Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (true) {
                        List<Integer> batch = new ArrayList<>();
                        batch.add(queue.take());
                        queue.drainTo(batch, 49);
                        if (bounded) window.acquire(batch.size());              // 在途额度：写完才归还
                        handedOff.addAndGet(batch.size());
                        writer.submit(() -> {
                            try { sinkBlocked.await(); } catch (InterruptedException e) { return; }
                            written.addAndGet(batch.size());
                            if (bounded) window.release(batch.size());
                        });
                    }
                } catch (InterruptedException ignored) { }
            });
            Thread producer = Thread.ofPlatform().daemon().start(() -> {
                try { for (int i = 0; i < 5000; i++) { queue.put(i); produced.incrementAndGet(); } } catch (InterruptedException ignored) { }
            });
            Thread.sleep(500);
            out("inflight." + (bounded ? "bounded" : "unbounded"),
                    (bounded ? "在途额度 200" : "不限在途") + "，下游卡住 500ms 后：生产者已放入 " + produced.get() + " 条，队列里 " + queue.size()
                            + " 条，已交给写入线程但未写完 " + (handedOff.get() - written.get()) + " 条，生产者" + (producer.isAlive() ? "被阻塞" : "已全部放完，没有被阻塞"));
            consumer.interrupt(); producer.interrupt(); writer.shutdownNow();
        }
    }

    /** 3. 按时间刷出：单条等待每次重新计时，与使用批的截止时间。 */
    static void flushByTime() throws Exception {
        for (boolean deadline : new boolean[] {false, true}) {
            BlockingQueue<Long> queue = new ArrayBlockingQueue<>(100);
            long[] firstItemDelay = {-1};
            int[] batchCount = {0};
            Thread consumer = Thread.ofPlatform().daemon().start(() -> {
                try {
                    List<Long> batch = new ArrayList<>();
                    batch.add(queue.take());
                    long batchDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                    while (batch.size() < 50) {
                        long wait = deadline ? batchDeadline - System.nanoTime() : TimeUnit.MILLISECONDS.toNanos(100);
                        if (wait <= 0) break;
                        Long next = queue.poll(wait, TimeUnit.NANOSECONDS);
                        if (next == null) break;
                        batch.add(next);
                    }
                    firstItemDelay[0] = (System.nanoTime() - batch.get(0)) / 1_000_000;
                    batchCount[0] = batch.size();
                } catch (InterruptedException ignored) { }
            });
            for (int i = 0; i < 10; i++) { queue.put(System.nanoTime()); Thread.sleep(80); }   // 每 80ms 来一条
            consumer.join(2000);
            out("flush." + (deadline ? "deadline" : "per_poll"),
                    (deadline ? "按批的截止时间等待" : "每次 poll(100ms) 重新计时") + "，每 80ms 到达一条：第一批 " + batchCount[0] + " 条，第一条等了 " + firstItemDelay[0] + "ms");
        }
    }

    /** 模拟下游：批里有毒数据时整批失败。 */
    static void write(List<Integer> batch, List<Integer> sink) {
        if (batch.contains(-1)) throw new IllegalArgumentException("bad row");
        sink.addAll(batch);
    }

    /** 4. 一批里有一条写不进去。 */
    static void failure() throws Exception {
        for (String mode : new String[] {"no_catch", "drop_batch", "per_item"}) {
            BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(100);
            List<Integer> sink = new CopyOnWriteArrayList<>(), dead = new CopyOnWriteArrayList<>();
            AtomicInteger takenGood = new AtomicInteger();
            for (int i = 0; i < 100; i++) queue.put(i == 37 ? -1 : i);          // 第 38 条是坏数据
            Thread consumer = Thread.ofPlatform().daemon().unstarted(() -> {
                try {
                    while (true) {
                        List<Integer> batch = new ArrayList<>();
                        batch.add(queue.take());
                        queue.drainTo(batch, 49);
                        takenGood.addAndGet((int) batch.stream().filter(v -> v >= 0 && v < 1000).count());
                        switch (mode) {
                            case "no_catch" -> write(batch, sink);
                            case "drop_batch" -> { try { write(batch, sink); } catch (RuntimeException e) { /* 只记日志 */ } }
                            default -> {
                                try { write(batch, sink); }
                                catch (RuntimeException e) {
                                    for (Integer item : batch) {
                                        try { write(List.of(item), sink); } catch (RuntimeException one) { dead.add(item); }
                                    }
                                }
                            }
                        }
                    }
                } catch (InterruptedException ignored) { }
            });
            consumer.setUncaughtExceptionHandler((t, e) -> { });
            consumer.start();
            Thread.sleep(200);
            boolean accepted = queue.offer(1000, 100, TimeUnit.MILLISECONDS);
            for (int i = 0; i < 150 && accepted; i++) accepted = queue.offer(1001 + i, 20, TimeUnit.MILLISECONDS);
            long written = sink.stream().filter(v -> v < 1000).count();
            out("failure." + mode, "99 条好数据加 1 条坏数据，批大小 50：写入 " + written + " 条，坏数据进死信 " + dead.size() + " 条，已取出但没写入的好数据 "
                    + (takenGood.get() - written) + " 条，留在队列里没人处理 " + (99 - takenGood.get()) + " 条；消费者线程存活=" + consumer.isAlive()
                    + "；之后生产者" + (accepted ? "仍能放入" : "放满队列后被阻塞（队列 " + queue.size() + " 条）"));
            consumer.interrupt();
        }
    }

    /** 5. 关闭：直接中断，与先停接收、排空、刷出最后一批。 */
    static void shutdown() throws Exception {
        for (boolean graceful : new boolean[] {false, true}) {
            BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(100);
            List<Integer> sink = new CopyOnWriteArrayList<>();
            AtomicBoolean stopping = new AtomicBoolean();
            CountDownLatch firstBatchTaken = new CountDownLatch(1), release = new CountDownLatch(1);
            for (int i = 0; i < 100; i++) queue.put(i);
            Thread consumer = Thread.ofPlatform().daemon().start(() -> {
                List<Integer> batch = new ArrayList<>();
                try {
                    while (true) {
                        Integer head = queue.poll(50, TimeUnit.MILLISECONDS);
                        if (head == null) { if (stopping.get()) return; else continue; }
                        batch.add(head);
                        queue.drainTo(batch, 19);
                        firstBatchTaken.countDown();
                        release.await();                                        // 第一批取出后、写入前，收到关闭
                        sink.addAll(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    if (graceful) {                                             // 排空：手里的批和队列里剩下的都写完
                        queue.drainTo(batch);
                        sink.addAll(batch);
                    }
                }
            });
            firstBatchTaken.await();
            stopping.set(true);                                                 // 上游从此不再放入
            consumer.interrupt();
            consumer.join(1000);
            out("shutdown." + (graceful ? "drain" : "interrupt"),
                    "队列 100 条，消费者已取出 20 条尚未写入时关闭，" + (graceful ? "中断后排空并写完" : "中断后直接退出") + "：写入 " + sink.size() + " 条，丢失 " + (100 - sink.size()) + " 条");
        }
    }

    /** 6. 毒丸：每个消费者要一颗；放不进满队列时不能用 offer 了事。 */
    static void poisonPill() throws Exception {
        final Integer PILL = Integer.MIN_VALUE;
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> consumers = new ArrayList<>();
        for (int c = 0; c < 2; c++) {
            consumers.add(Thread.ofPlatform().daemon().start(() -> {
                try { gate.await(); while (!queue.take().equals(PILL)) { } } catch (InterruptedException ignored) { }
            }));
        }
        for (int i = 0; i < 10; i++) queue.put(i);                              // 队列是满的
        boolean offered = queue.offer(PILL);
        out("pill.offer_full", "队列已满时 offer(毒丸) 返回 " + offered + "：毒丸没有进队列");
        gate.countDown();
        queue.put(PILL);                                                        // 只放一颗
        Thread.sleep(300);
        out("pill.one_for_two", "2 个消费者只放 1 颗毒丸：仍在运行的消费者 " + consumers.stream().filter(Thread::isAlive).count() + " 个");
        queue.put(PILL);
        Thread.sleep(300);
        out("pill.one_each", "补上第 2 颗之后：仍在运行的消费者 " + consumers.stream().filter(Thread::isAlive).count() + " 个");
    }
}
