import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 组合获取多把锁是一个可失败的协议：部分成功要回退、回退本身可能失败；相反顺序会互相等待；
 * 逐个获取消耗前面锁的租期；在多个节点上拿到多数派，节点丢失状态重启后可能出现两个持有者。
 */
public class Lab {

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    /** 带租约的锁服务（单个节点），时间由测试推进。 */
    static final class LeaseLocks {
        final Map<String, String> owner = new HashMap<>();
        final Map<String, Long> expires = new HashMap<>();
        boolean failNextRelease;

        boolean tryAcquire(String key, String who, long now, long lease) {
            Long exp = expires.get(key);
            if (exp != null && exp > now && !who.equals(owner.get(key))) {
                return false;
            }
            owner.put(key, who);
            expires.put(key, now + lease);
            return true;
        }

        void release(String key, String who) {
            if (failNextRelease) {
                failNextRelease = false;
                throw new IllegalStateException("释放 " + key + " 时网络超时");
            }
            if (who.equals(owner.get(key))) {
                owner.remove(key);
                expires.remove(key);
            }
        }

        String holder(String key, long now) {
            Long exp = expires.get(key);
            return exp != null && exp > now ? owner.get(key) : "无";
        }
    }

    public static void main(String[] args) throws Exception {
        partialSuccess();
        lockOrdering();
        leaseBudget();
        majority();
    }

    static void partialSuccess() {
        // 转账要同时锁住两个账户
        LeaseLocks locks = new LeaseLocks();
        long now = 0;
        locks.tryAcquire("acct:B", "其他请求", now, 10_000);
        boolean a = locks.tryAcquire("acct:A", "转账-1", now, 10_000);
        boolean b = locks.tryAcquire("acct:B", "转账-1", now, 10_000);
        out("partial.naive", "锁 A 成功=" + a + "、锁 B 失败=" + !b + "，朴素实现直接返回失败：acct:A 的持有者仍是「" + locks.holder("acct:A", now)
                + "」，要等 10 秒租期到期才释放");

        LeaseLocks locks2 = new LeaseLocks();
        locks2.tryAcquire("acct:B", "其他请求", now, 10_000);
        List<String> acquired = new ArrayList<>();
        for (String k : List.of("acct:A", "acct:B")) {
            if (locks2.tryAcquire(k, "转账-1", now, 10_000)) {
                acquired.add(k);
            } else {
                for (int i = acquired.size() - 1; i >= 0; i--) {
                    locks2.release(acquired.get(i), "转账-1");
                }
                acquired.clear();
                break;
            }
        }
        out("partial.rollback", "第二把失败时逆序释放已拿到的：acct:A 的持有者 " + locks2.holder("acct:A", now));

        LeaseLocks locks3 = new LeaseLocks();
        locks3.tryAcquire("acct:B", "其他请求", now, 10_000);
        locks3.tryAcquire("acct:A", "转账-1", now, 10_000);
        locks3.failNextRelease = true;
        String rollback;
        try {
            locks3.release("acct:A", "转账-1");
            rollback = "已释放";
        } catch (IllegalStateException e) {
            rollback = "释放失败（" + e.getMessage() + "），acct:A 现在是「" + locks3.holder("acct:A", now) + "」，10 秒后为「" + locks3.holder("acct:A", 10_001) + "」";
        }
        out("partial.release-fails", "回退本身也可能失败：" + rollback + "，所以每把锁都必须有租期兜底");
    }

    static void lockOrdering() throws Exception {
        ReentrantLock a = new ReentrantLock();
        ReentrantLock b = new ReentrantLock();
        CountDownLatch bothHoldFirst = new CountDownLatch(2);
        Thread t1 = Thread.ofPlatform().start(() -> lockBoth(a, b, bothHoldFirst));
        Thread t2 = Thread.ofPlatform().start(() -> lockBoth(b, a, bothHoldFirst));
        t1.join(500);
        t2.join(500);
        boolean stuck = t1.isAlive() && t2.isAlive();
        t1.interrupt();
        t2.interrupt();
        t1.join();
        t2.join();
        out("order.opposite", "线程一先 A 后 B，线程二先 B 后 A，都拿到第一把后等第二把：500ms 后两个线程都还在等=" + stuck);

        ReentrantLock c = new ReentrantLock();
        ReentrantLock d = new ReentrantLock();
        Map<String, ReentrantLock> byName = Map.of("acct:C", c, "acct:D", d);
        List<String> done = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable sorted1 = () -> sortedLock(byName, List.of("acct:C", "acct:D"), done, "线程一");
        Runnable sorted2 = () -> sortedLock(byName, List.of("acct:D", "acct:C"), done, "线程二");
        Thread t3 = Thread.ofPlatform().start(sorted1);
        Thread t4 = Thread.ofPlatform().start(sorted2);
        t3.join(2000);
        t4.join(2000);
        out("order.sorted", "两个线程请求的顺序相反，但都先按名字排序再加锁：完成 " + done.size() + " 个，没有互相等待");
    }

    static void lockBoth(ReentrantLock first, ReentrantLock second, CountDownLatch bothHoldFirst) {
        try {
            first.lockInterruptibly();
            try {
                bothHoldFirst.countDown();
                bothHoldFirst.await();
                second.lockInterruptibly();
                second.unlock();
            } finally {
                first.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void sortedLock(Map<String, ReentrantLock> byName, List<String> wanted, List<String> done, String who) {
        List<String> keys = new ArrayList<>(wanted);
        keys.sort(Comparator.naturalOrder());
        List<ReentrantLock> held = new ArrayList<>();
        try {
            for (String k : keys) {
                ReentrantLock l = byName.get(k);
                if (!l.tryLock(1, TimeUnit.SECONDS)) {
                    return;
                }
                held.add(l);
            }
            done.add(who);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    static void leaseBudget() {
        // 三把锁在三个节点上，每把租期 10 秒，每次获取（含网络往返与重试）耗时 3 秒
        LeaseLocks locks = new LeaseLocks();
        long now = 0;
        List<Long> expiries = new ArrayList<>();
        for (String k : List.of("stock:1", "stock:2", "stock:3")) {
            locks.tryAcquire(k, "批量扣减", now, 10_000);
            expiries.add(now + 10_000);
            now += 3_000;
        }
        long remaining = expiries.stream().mapToLong(Long::longValue).min().orElseThrow() - now;
        out("lease.budget", "逐个获取三把锁、每次耗时 3 秒、租期 10 秒：全部拿到时，第一把只剩 " + remaining / 1000 + " 秒；业务需要 5 秒 → "
                + (remaining >= 5_000 ? "继续" : "放弃并释放（否则第一把会在业务中途过期）"));
        out("lease.mid-operation", "如果不检查直接执行 5 秒：执行结束时 stock:1 的持有者是「" + locks.holder("stock:1", now + 5_000) + "」");
    }

    static void majority() {
        // 三个独立节点，拿到两个就算获得锁
        List<LeaseLocks> nodes = List.of(new LeaseLocks(), new LeaseLocks(), new LeaseLocks());
        long now = 0;
        int c1 = 0;
        for (LeaseLocks n : nodes.subList(0, 2)) { // 客户端一拿到节点 1、2，节点 3 暂时不可达
            if (n.tryAcquire("job", "客户端一", now, 30_000)) {
                c1++;
            }
        }
        // 节点 2 崩溃后立即重启，锁状态只在内存里，丢了
        LeaseLocks restarted = new LeaseLocks();
        List<LeaseLocks> after = List.of(nodes.get(0), restarted, nodes.get(2));
        int c2 = 0;
        for (LeaseLocks n : after) {
            if (n.tryAcquire("job", "客户端二", now + 1_000, 30_000)) {
                c2++;
            }
        }
        out("majority.restart", "客户端一在 3 个节点里拿到 " + c1 + " 个；节点 2 丢失状态后立即重启，客户端二拿到 " + c2 + " 个：两个客户端都认为自己持有锁");
        out("majority.note", "多数派获取依赖「节点重启后等待一个租期再服务」等前提；它不是共识复制，也不给资源端提供 fencing 令牌");
    }
}
