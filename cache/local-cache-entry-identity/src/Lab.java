import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 进程内缓存与登记表的条目身份：按 key 删除删掉了新值、旧连接注销了新会话、淘汰方向、并发加载、
 * 监听器异常、缓存可变对象；最后用 Caffeine 对照同样的场景。
 */
public class Lab {

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    record Entry(String value, long version) {}

    public static void main(String[] args) throws Exception {
        expiryRemovesNewValue();
        sessionRegistry();
        evictionDirection();
        concurrentLoad();
        listenerFailure();
        mutableValue();
        caffeine();
    }

    /** 过期任务在写入时登记，执行时按 key 删除。 */
    static void expiryRemovesNewValue() {
        Map<String, Entry> naive = new ConcurrentHashMap<>();
        List<Runnable> timers = new ArrayList<>();
        Consumer<Entry> putNaive = e -> {
            naive.put("price:42", e);
            timers.add(() -> naive.remove("price:42"));
        };
        putNaive.accept(new Entry("99 元", 1));
        putNaive.accept(new Entry("89 元", 2)); // 降价后刷新
        timers.get(0).run(); // 第一次写入的过期任务到点
        out("expiry.by-key", "第一次写入的过期任务按 key 删除：降价后刚写入的 89 元也被删了，缓存里是 " + naive.get("price:42"));

        Map<String, Entry> fixed = new ConcurrentHashMap<>();
        List<Runnable> timers2 = new ArrayList<>();
        Consumer<Entry> putFixed = e -> {
            fixed.put("price:42", e);
            timers2.add(() -> fixed.remove("price:42", e)); // 只删当时写入的那个条目
        };
        putFixed.accept(new Entry("99 元", 1));
        putFixed.accept(new Entry("89 元", 2));
        timers2.get(0).run();
        out("expiry.by-entry", "过期任务只删除自己登记时的条目 remove(key, entry)：缓存里仍是 " + fixed.get("price:42"));
    }

    /** 同一用户断线重连：新连接先登记，旧连接的关闭回调后到。 */
    static void sessionRegistry() {
        Map<String, String> online = new ConcurrentHashMap<>();
        online.put("u1", "channel-1");
        online.put("u1", "channel-2"); // 重连
        online.remove("u1"); // channel-1 的关闭回调
        out("session.by-key", "用户重连后旧连接的关闭回调按用户删除：u1 的在线连接 " + online.get("u1") + "（新连接还活着，却被标成离线）");

        Map<String, String> online2 = new ConcurrentHashMap<>();
        online2.put("u1", "channel-1");
        online2.put("u1", "channel-2");
        boolean removed = online2.remove("u1", "channel-1");
        out("session.by-entry", "关闭回调只删除自己 remove(u1, channel-1)：删除=" + removed + "，u1 的在线连接 " + online2.get("u1"));
    }

    /** 按命中次数淘汰：排序方向写反时删掉的是最热的条目。 */
    static void evictionDirection() {
        Map<String, Integer> hits = new ConcurrentHashMap<>(Map.of("首页", 120, "详情", 30, "冷门", 1));
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(hits.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        String naiveVictim = sorted.get(0).getKey();
        sorted.sort(Map.Entry.comparingByValue());
        String fixedVictim = sorted.get(0).getKey();
        out("evict.direction", "命中次数 首页 120、详情 30、冷门 1，容量满时删除排序后的第一个：降序排序删掉「" + naiveVictim + "」，升序删掉「" + fixedVictim + "」");
    }

    /** 四个线程同时未命中同一个 key。 */
    static void concurrentLoad() throws Exception {
        Map<String, String> naive = new ConcurrentHashMap<>();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch allMissed = new CountDownLatch(4);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                String v = naive.get("report");
                allMissed.countDown();
                try {
                    allMissed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (v == null) {
                    loads.incrementAndGet(); // 回源：一次昂贵的查询
                    naive.put("report", "日报");
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        out("load.get-then-put", "四个线程同时未命中、各自 get 后回源再 put：回源 " + loads.get() + " 次");

        Map<String, String> once = new ConcurrentHashMap<>();
        AtomicInteger loads2 = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads2.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                once.computeIfAbsent("report", k -> {
                    loads2.incrementAndGet();
                    return "日报";
                });
            }));
        }
        go.countDown();
        for (Thread t : threads2) {
            t.join();
        }
        out("load.compute", "同一个 key 用 computeIfAbsent 加载：回源 " + loads2.get() + " 次");
    }

    /** 写入后通知监听器，监听器抛错。 */
    static void listenerFailure() {
        Map<String, String> store = new ConcurrentHashMap<>();
        List<Consumer<String>> listeners = List.of(k -> {
            throw new IllegalStateException("通知下游失败");
        });
        String result;
        try {
            store.put("cfg", "v2");
            listeners.forEach(l -> l.accept("cfg"));
            result = "成功";
        } catch (RuntimeException e) {
            result = "失败（" + e.getMessage() + "）";
        }
        out("listener.inline", "put 之后同步调用监听器，监听器抛错：调用方看到" + result + "，缓存里却已经是 " + store.get("cfg"));
    }

    static final class Profile {
        String level = "普通";
    }

    /** 缓存直接返回内部对象。 */
    static void mutableValue() {
        Map<String, Profile> cache = new ConcurrentHashMap<>();
        cache.put("u7", new Profile());
        Profile p = cache.get("u7");
        p.level = "VIP（只用于这次活动的试算）";
        out("mutable", "调用方拿到缓存里的对象后改了一个字段：下一个读者看到的等级是「" + cache.get("u7").level + "」");
    }

    /** Caffeine 3.3.1 对照：可控时钟、加载、移除监听器。 */
    static void caffeine() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Cache<String, String> cache = Caffeine.newBuilder()
                .ticker(nanos::get)
                .expireAfterWrite(Duration.ofSeconds(10))
                .executor(Runnable::run)
                .removalListener((String k, String v, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    throw new IllegalStateException("监听器失败");
                })
                .build();
        cache.put("price:42", "99 元");
        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        cache.put("price:42", "89 元");
        nanos.addAndGet(Duration.ofSeconds(6).toNanos()); // 距第一次写入 11 秒，距第二次 6 秒
        out("caffeine.expiry", "Caffeine：第一次写入 11 秒后（第二次写入 6 秒后）读取：" + cache.getIfPresent("price:42") + "（过期按条目计算）");

        AtomicInteger loads = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Cache<String, String> loading = Caffeine.newBuilder().build();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                loading.get("report", k -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "日报";
                });
            }));
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        out("caffeine.load", "Caffeine：四个线程同时 get(key, loader)，加载耗时 200ms：回源 " + loads.get() + " 次");

        boolean returned;
        try {
            cache.invalidate("price:42");
            returned = true;
        } catch (RuntimeException e) {
            returned = false;
        }
        out("caffeine.listener", "Caffeine：移除监听器抛错时 invalidate 正常返回=" + returned + "，条目已移除=" + (cache.getIfPresent("price:42") == null));

        Cache<String, Profile> profiles = Caffeine.newBuilder().build();
        profiles.put("u7", new Profile());
        profiles.getIfPresent("u7").level = "VIP（试算）";
        out("caffeine.mutable", "Caffeine 同样返回同一个对象：被改后的等级「" + profiles.getIfPresent("u7").level + "」");
    }
}
