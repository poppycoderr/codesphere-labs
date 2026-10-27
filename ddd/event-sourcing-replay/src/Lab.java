import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 事件溯源的最小实现：命令经 decide 产生事件，状态只由 evolve 从事件演算；
 * 事件存储按聚合版本做期望版本检查，投影各自维护检查点，快照加后续事件与完整重放等价。
 */
public class Lab {

    sealed interface Event permits Opened, Messaged, Closed {}

    record Opened(String customer, String consultationId, Instant at) implements Event {}

    record Messaged(String text, Instant at) implements Event {}

    record Closed(int rating, Instant at) implements Event {}

    /** 全局位置 position 供投影做检查点；version 是事件在本聚合流中的序号。 */
    record Stored(long position, String stream, long version, Event event) {}

    sealed interface Command permits Open, Append, Close {}

    record Open(String customer) implements Command {}

    record Append(String text) implements Command {}

    record Close(int rating) implements Command {}

    static final class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }

    static final class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }

    static final class Store {
        final List<Stored> log = new ArrayList<>();

        List<Stored> load(String stream) {
            return log.stream().filter(s -> s.stream().equals(stream)).toList();
        }

        /** 只有当前版本等于调用方读到的版本时才追加；否则说明别人先写了。 */
        long append(String stream, long expectedVersion, List<Event> events) {
            long current = load(stream).size();
            if (current != expectedVersion) {
                throw new Conflict("期望版本 " + expectedVersion + "，实际版本 " + current);
            }
            for (Event e : events) {
                log.add(new Stored(log.size() + 1, stream, ++current, e));
            }
            return current;
        }
    }

    record Ticket(
            String customer,
            String consultationId,
            Instant openedAt,
            List<String> messages,
            boolean closed,
            Integer rating,
            long version) {
        static final Ticket NONE = new Ticket(null, null, null, List.of(), false, null, 0);

        boolean opened() {
            return customer != null;
        }
    }

    /** 正确的演算：只使用事件里已经固定下来的数据。 */
    static Ticket evolve(Ticket t, Event e) {
        return switch (e) {
            case Opened o -> new Ticket(o.customer(), o.consultationId(), o.at(), List.of(), false, null, t.version() + 1);
            case Messaged m -> {
                var messages = new ArrayList<>(t.messages());
                messages.add(m.text());
                yield new Ticket(t.customer(), t.consultationId(), t.openedAt(), List.copyOf(messages), t.closed(), t.rating(), t.version() + 1);
            }
            case Closed c -> new Ticket(t.customer(), t.consultationId(), t.openedAt(), t.messages(), true, c.rating(), t.version() + 1);
        };
    }

    static Ticket replay(Ticket start, List<Stored> events, BiFunction<Ticket, Event, Ticket> evolve) {
        Ticket t = start;
        for (Stored s : events) {
            t = evolve.apply(t, s.event());
        }
        return t;
    }

    static List<Event> decide(Ticket t, Command c, Supplier<String> ids, Supplier<Instant> now) {
        return switch (c) {
            case Open o -> {
                if (t.opened()) {
                    throw new Rejected("工单已存在");
                }
                yield List.of(new Opened(o.customer(), ids.get(), now.get()));
            }
            case Append a -> {
                if (!t.opened()) {
                    throw new Rejected("工单不存在");
                }
                if (t.closed()) {
                    throw new Rejected("工单已关闭，不能再追加消息");
                }
                yield List.of(new Messaged(a.text(), now.get()));
            }
            case Close cl -> {
                if (!t.opened()) {
                    throw new Rejected("工单不存在");
                }
                if (t.closed()) {
                    throw new Rejected("工单已关闭");
                }
                if (cl.rating() < 0 || cl.rating() > 5) {
                    throw new Rejected("评分必须在 0 到 5 之间，收到 " + cl.rating());
                }
                yield List.of(new Closed(cl.rating(), now.get()));
            }
        };
    }

    /** 可重复的「随机」来源与时钟：每次调用都给出新值，用来模拟 UUID 与 Instant.now()。 */
    static Supplier<String> ids(String prefix) {
        int[] n = {0};
        return () -> prefix + "-" + (++n[0]);
    }

    static Supplier<Instant> clock() {
        Instant[] t = {Instant.parse("2026-10-27T09:00:00Z")};
        return () -> t[0] = t[0].plus(Duration.ofMinutes(1));
    }

    static long handle(Store store, String id, Command c, Supplier<String> ids, Supplier<Instant> now) {
        Ticket t = replay(Ticket.NONE, store.load(id), Lab::evolve);
        return store.append(id, t.version(), decide(t, c, ids, now));
    }

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    public static void main(String[] args) {
        replayDeterminism();
        rejection();
        concurrency();
        projections();
        snapshot();
    }

    static void replayDeterminism() {
        // 朴素写法：决定时不产生编号和时间，演算时现取（相当于在 on/apply 里调用 UUID.randomUUID() 与 Instant.now()）
        Supplier<String> naiveIds = ids("C");
        Supplier<Instant> naiveNow = clock();
        BiFunction<Ticket, Event, Ticket> naiveEvolve = (t, e) -> e instanceof Opened o
                ? new Ticket(o.customer(), naiveIds.get(), naiveNow.get(), List.of(), false, null, t.version() + 1)
                : evolve(t, e);
        Store naive = new Store();
        naive.append("T1", 0, List.of(new Opened("alice", null, null)));
        Ticket a = replay(Ticket.NONE, naive.load("T1"), naiveEvolve);
        Ticket b = replay(Ticket.NONE, naive.load("T1"), naiveEvolve);
        out("replay.naive", "在演算里生成咨询编号、取当前时间：同一条事件流重建两次，咨询编号 " + a.consultationId() + " 与 " + b.consultationId()
                + "，开单时间 " + a.openedAt() + " 与 " + b.openedAt() + "，两次状态相等=" + a.equals(b));

        Store fixed = new Store();
        handle(fixed, "T1", new Open("alice"), ids("C"), clock());
        Ticket c = replay(Ticket.NONE, fixed.load("T1"), Lab::evolve);
        Ticket d = replay(Ticket.NONE, fixed.load("T1"), Lab::evolve);
        out("replay.fixed", "编号和时间在决定时写进事件：重建两次，咨询编号都是 " + c.consultationId() + "，两次状态相等=" + c.equals(d));
    }

    static void rejection() {
        Store store = new Store();
        Supplier<String> ids = ids("C");
        Supplier<Instant> now = clock();
        handle(store, "T2", new Open("bob"), ids, now);
        int step = 0;
        for (Command c : List.of(new Close(7), new Close(4), new Append("还有一个问题"), new Close(5))) {
            int before = store.load("T2").size();
            String result;
            try {
                handle(store, "T2", c, ids, now);
                result = "接受";
            } catch (Rejected e) {
                result = "拒绝（" + e.getMessage() + "）";
            }
            out("reject." + (++step), c + " → " + result + "，事件数 " + before + " → " + store.load("T2").size());
        }
    }

    static void concurrency() {
        Store store = new Store();
        Supplier<String> ids = ids("C");
        Supplier<Instant> now = clock();
        handle(store, "T3", new Open("carol"), ids, now);
        handle(store, "T3", new Append("打不开订单页"), ids, now);
        // 两个处理者在同一时刻读到版本 2
        Ticket seenByA = replay(Ticket.NONE, store.load("T3"), Lab::evolve);
        Ticket seenByB = replay(Ticket.NONE, store.load("T3"), Lab::evolve);
        long v = store.append("T3", seenByA.version(), decide(seenByA, new Close(5), ids, now));
        out("conflict.first", "处理者 A 读到版本 " + seenByA.version() + "，关闭工单，追加成功，版本变为 " + v);
        try {
            store.append("T3", seenByB.version(), decide(seenByB, new Append("补充截图"), ids, now));
        } catch (Conflict e) {
            out("conflict.second", "处理者 B 也读到版本 " + seenByB.version() + "，追加消息被拒：" + e.getMessage());
        }
        try {
            handle(store, "T3", new Append("补充截图"), ids, now);
        } catch (Rejected e) {
            out("conflict.retry", "处理者 B 重新加载后再决定：" + e.getMessage() + "；流中事件数 " + store.load("T3").size());
        }
    }

    /** 读模型：每张工单一行摘要；检查点是已处理的最大全局位置。 */
    static final class Summary {
        final Map<String, int[]> messages = new TreeMap<>();
        final Map<String, String> state = new TreeMap<>();
        long checkpoint;
        final boolean useCheckpoint;

        Summary(boolean useCheckpoint) {
            this.useCheckpoint = useCheckpoint;
        }

        void apply(Stored s) {
            if (useCheckpoint && s.position() <= checkpoint) {
                return;
            }
            switch (s.event()) {
                case Opened o -> {
                    messages.put(s.stream(), new int[] {0});
                    state.put(s.stream(), "处理中");
                }
                case Messaged m -> messages.get(s.stream())[0]++;
                case Closed c -> state.put(s.stream(), "已关闭，评分 " + c.rating());
            }
            checkpoint = Math.max(checkpoint, s.position());
        }

        String render() {
            var sb = new StringBuilder();
            state.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "；").append(k).append(" ").append(messages.get(k)[0]).append(" 条消息、").append(v));
            return sb.toString();
        }
    }

    static void projections() {
        Store store = new Store();
        Supplier<String> ids = ids("C");
        Supplier<Instant> now = clock();
        handle(store, "T4", new Open("dave"), ids, now);
        handle(store, "T4", new Append("退款没到账"), ids, now);
        handle(store, "T4", new Close(4), ids, now);
        handle(store, "T5", new Open("erin"), ids, now);
        handle(store, "T5", new Append("发票抬头错了"), ids, now);

        List<String> mailbox = new ArrayList<>();
        // 朴素写法：同一个处理器既更新摘要，又在看到 Closed 时发满意度回访
        Summary naive = new Summary(false);
        for (Stored s : store.log) {
            naive.apply(s);
            if (s.event() instanceof Closed) {
                mailbox.add("回访 " + s.stream());
            }
        }
        int afterLive = mailbox.size();
        Summary rebuilt = new Summary(false);
        for (Stored s : store.log) {
            rebuilt.apply(s);
            if (s.event() instanceof Closed) {
                mailbox.add("回访 " + s.stream());
            }
        }
        out("projection.naive", "摘要与回访写在一个处理器里：首次处理后发出回访 " + afterLive + " 封，为修复读模型从头重建后共 " + mailbox.size() + " 封");

        // 分开订阅：摘要投影与回访策略各有检查点，重建只重置摘要的检查点
        List<String> mailbox2 = new ArrayList<>();
        Summary summary = new Summary(true);
        long[] surveyCheckpoint = {0};
        for (Stored s : store.log) {
            summary.apply(s);
            if (s.position() > surveyCheckpoint[0]) {
                if (s.event() instanceof Closed) {
                    mailbox2.add("回访 " + s.stream());
                }
                surveyCheckpoint[0] = s.position();
            }
        }
        String before = summary.render();
        Summary fresh = new Summary(true);
        store.log.forEach(fresh::apply);
        out("projection.split", "摘要投影与回访策略分开订阅：重建摘要后内容相同=" + before.equals(fresh.render()) + "，回访仍是 " + mailbox2.size()
                + " 封（回访策略的检查点停在 " + surveyCheckpoint[0] + "）");

        // 至少一次投递：同一条事件送达两次
        Stored message = store.log.get(1);
        Summary noCheck = new Summary(false);
        Summary withCheck = new Summary(true);
        for (Stored s : List.of(store.log.get(0), message, message)) {
            noCheck.apply(s);
            withCheck.apply(s);
        }
        out("projection.duplicate", "同一条消息事件投递两次：没有检查点时 " + noCheck.render() + "；按全局位置跳过已处理的 " + withCheck.render());
        out("projection.view", "当前摘要：" + before);
    }

    static void snapshot() {
        Store store = new Store();
        Supplier<String> ids = ids("C");
        Supplier<Instant> now = clock();
        handle(store, "T6", new Open("frank"), ids, now);
        for (int i = 1; i <= 4; i++) {
            handle(store, "T6", new Append("第 " + i + " 条"), ids, now);
        }
        handle(store, "T6", new Close(3), ids, now);
        List<Stored> all = store.load("T6");
        Ticket full = replay(Ticket.NONE, all, Lab::evolve);
        Ticket snap = replay(Ticket.NONE, all.subList(0, 4), Lab::evolve);
        List<Stored> tail = all.stream().filter(s -> s.version() > snap.version()).toList();
        Ticket fromSnapshot = replay(snap, tail, Lab::evolve);
        out("snapshot", "共 " + all.size() + " 个事件；在版本 " + snap.version() + " 做快照，恢复时读快照加 " + tail.size()
                + " 个后续事件，结果与完整重放相等=" + full.equals(fromSnapshot));
    }
}
