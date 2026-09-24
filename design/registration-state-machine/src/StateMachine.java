import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 活动报名的状态机：散落在方法里的条件分支与集中的转换表对比、全矩阵与可达性检查、并发命令与版本号、副作用失败后的重试。
 * 运行：java StateMachine，每行输出一个可断言的结果。
 */
public class StateMachine {

    enum State { PENDING, WAITLISTED, CONFIRMED, CANCELLED, CHECKED_IN }

    enum Command { CONFIRM, WAITLIST, PROMOTE, CANCEL, CHECK_IN }

    // ---------------- 集中的转换表 ----------------
    static final Map<State, Map<Command, State>> TABLE = new EnumMap<>(State.class);

    static {
        for (State s : State.values()) TABLE.put(s, new EnumMap<>(Command.class));
        TABLE.get(State.PENDING).put(Command.CONFIRM, State.CONFIRMED);
        TABLE.get(State.PENDING).put(Command.WAITLIST, State.WAITLISTED);
        TABLE.get(State.PENDING).put(Command.CANCEL, State.CANCELLED);
        TABLE.get(State.WAITLISTED).put(Command.PROMOTE, State.CONFIRMED);
        TABLE.get(State.WAITLISTED).put(Command.CANCEL, State.CANCELLED);
        TABLE.get(State.CONFIRMED).put(Command.CANCEL, State.CANCELLED);
        TABLE.get(State.CONFIRMED).put(Command.CHECK_IN, State.CHECKED_IN);
    }

    static Optional<State> table(State s, Command c) {
        return Optional.ofNullable(TABLE.get(s).get(c));
    }

    // ---------------- 散落的条件分支（模拟随需求逐步加上的写法） ----------------
    static Optional<State> scattered(State s, Command c) {
        switch (c) {
            case CONFIRM:
                if (s == State.PENDING || s == State.WAITLISTED) return Optional.of(State.CONFIRMED);
                return Optional.empty();
            case WAITLIST:
                if (s != State.CANCELLED) return Optional.of(State.WAITLISTED);
                return Optional.empty();
            case PROMOTE:
                if (s == State.WAITLISTED || s == State.CANCELLED) return Optional.of(State.CONFIRMED);
                return Optional.empty();
            case CANCEL:
                if (s != State.CANCELLED) return Optional.of(State.CANCELLED);
                return Optional.empty();
            case CHECK_IN:
                if (s == State.CONFIRMED) return Optional.of(State.CHECKED_IN);
                return Optional.empty();
            default:
                return Optional.empty();
        }
    }

    static void out(String key, Object value) {
        System.out.println(key + "\t" + value);
    }

    public static void main(String[] args) throws Exception {
        matrix();
        reachability();
        concurrency();
        sideEffects();
    }

    static void matrix() {
        int legal = 0;
        List<String> diffs = new ArrayList<>();
        StringBuilder grid = new StringBuilder("state\\command");
        for (Command c : Command.values()) grid.append('\t').append(c);
        for (State s : State.values()) {
            grid.append('\n').append(s);
            for (Command c : Command.values()) {
                Optional<State> t = table(s, c);
                Optional<State> x = scattered(s, c);
                if (t.isPresent()) legal++;
                grid.append('\t').append(t.map(Enum::name).orElse("-"));
                if (!t.equals(x)) diffs.add(s + "+" + c + ": 表 " + t.map(Enum::name).orElse("拒绝") + " / 分支 " + x.map(Enum::name).orElse("拒绝"));
            }
        }
        System.err.println(grid);
        out("matrix.size", State.values().length + " 个状态 × " + Command.values().length + " 个命令 = " + State.values().length * Command.values().length + " 个组合，合法 " + legal + " 个");
        out("matrix.scattered_diffs", diffs.size() + " 处不一致：" + String.join("；", diffs));
    }

    static void reachability() {
        Set<State> seen = EnumSet.of(State.PENDING);
        ArrayDeque<State> q = new ArrayDeque<>(List.of(State.PENDING));
        while (!q.isEmpty()) {
            for (State n : TABLE.get(q.poll()).values()) if (seen.add(n)) q.add(n);
        }
        Set<State> unreachable = EnumSet.complementOf(EnumSet.copyOf(seen));
        List<State> terminal = new ArrayList<>();
        for (State s : State.values()) if (TABLE.get(s).isEmpty()) terminal.add(s);
        out("graph.reachability", "从 PENDING 不可达的状态 " + unreachable + "；没有出口的状态 " + terminal);
    }

    // ---------------- 并发 ----------------
    record Snapshot(State state, long version) {
    }

    /** 读出状态与写回之间的一次存储往返，用 0.2ms 模拟。 */
    static void roundTrip() {
        java.util.concurrent.locks.LockSupport.parkNanos(200_000);
    }

    /** 1000 轮：两个线程同时对 CONFIRMED 的报名执行 CANCEL 与 CHECK_IN。 */
    static void concurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int rounds = 1000;
        int bothAppliedNaive = 0;
        int bothAppliedVersioned = 0;
        int conflicts = 0;
        for (int i = 0; i < rounds; i++) {
            // 不带版本号：读出状态、判断、写回
            AtomicReference<State> naive = new AtomicReference<>(State.CONFIRMED);
            AtomicInteger applied = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            for (Command c : List.of(Command.CANCEL, Command.CHECK_IN)) {
                pool.execute(() -> {
                    try {
                        go.await();
                        State current = naive.get();
                        Optional<State> next = table(current, c);
                        roundTrip();
                        if (next.isPresent()) {
                            naive.set(next.get());
                            applied.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            done.await();
            if (applied.get() == 2) bothAppliedNaive++;

            // 带版本号：比较并设置 (状态, 版本)
            AtomicReference<Snapshot> row = new AtomicReference<>(new Snapshot(State.CONFIRMED, 1));
            AtomicInteger appliedV = new AtomicInteger();
            AtomicInteger conflictV = new AtomicInteger();
            CountDownLatch go2 = new CountDownLatch(1);
            CountDownLatch done2 = new CountDownLatch(2);
            for (Command c : List.of(Command.CANCEL, Command.CHECK_IN)) {
                pool.execute(() -> {
                    try {
                        go2.await();
                        Snapshot current = row.get();
                        Optional<State> next = table(current.state(), c);
                        roundTrip();
                        if (next.isEmpty()) {
                            conflictV.incrementAndGet();
                        } else if (row.compareAndSet(current, new Snapshot(next.get(), current.version() + 1))) {
                            appliedV.incrementAndGet();
                        } else {
                            conflictV.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done2.countDown();
                    }
                });
            }
            go2.countDown();
            done2.await();
            if (appliedV.get() == 2) bothAppliedVersioned++;
            conflicts += conflictV.get();
        }
        pool.shutdown();
        out("concurrency.naive", rounds + " 轮 CANCEL 与 CHECK_IN 并发：两个命令都「成功」" + bothAppliedNaive + " 轮");
        out("concurrency.versioned", rounds + " 轮带版本号：两个命令都成功 " + bothAppliedVersioned + " 轮，另一个命令被拒绝 " + conflicts + " 次");
    }

    // ---------------- 副作用 ----------------
    /** 确认后要给报名人发通知；通知服务前 2 次调用失败。 */
    static final class Mailer {
        int calls;
        final List<String> delivered = new ArrayList<>();

        void send(String id) {
            calls++;
            if (calls <= 2) throw new IllegalStateException("邮件服务超时");
            delivered.add(id);
        }
    }

    static void sideEffects() {
        // 做法一：状态改完后直接调用通知；失败时让调用方重试整个命令
        {
            Mailer mailer = new Mailer();
            State[] state = {State.PENDING};
            List<String> results = new ArrayList<>();
            for (int attempt = 1; attempt <= 3; attempt++) {
                Optional<State> next = table(state[0], Command.CONFIRM);
                if (next.isEmpty()) {
                    results.add("第" + attempt + "次：CONFIRM 在 " + state[0] + " 下非法");
                    continue;
                }
                state[0] = next.get();
                try {
                    mailer.send("r1");
                    results.add("第" + attempt + "次：已通知");
                } catch (IllegalStateException e) {
                    results.add("第" + attempt + "次：状态已变为 " + state[0] + "，通知失败");
                }
            }
            out("side_effect.inline", String.join("；", results) + "；最终送达 " + mailer.delivered.size() + " 封");
        }
        // 做法二：状态变化与「待发送通知」一起写入，由投递器重试
        {
            Mailer mailer = new Mailer();
            State state = table(State.PENDING, Command.CONFIRM).orElseThrow();
            List<String> outbox = new ArrayList<>(List.of("r1"));
            int rounds = 0;
            while (!outbox.isEmpty() && rounds < 5) {
                rounds++;
                try {
                    mailer.send(outbox.get(0));
                    outbox.remove(0);
                } catch (IllegalStateException e) {
                    // 留在待发送列表，下一轮再投递
                }
            }
            out("side_effect.outbox", "状态 " + state + "；投递器第 " + rounds + " 轮送达，调用通知服务 " + mailer.calls + " 次，最终送达 " + mailer.delivered.size() + " 封");
        }
    }
}
