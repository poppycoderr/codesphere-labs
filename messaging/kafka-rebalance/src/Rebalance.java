import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.*;

/**
 * 消费组重平衡，输出为「键<TAB>事实」。参数：bootstrap 场景 [协议]。
 * 协议：eager（经典协议 + RangeAssignor，客户端默认）、coop（经典协议 + CooperativeStickyAssignor）、consumer（KIP-848 新协议）。
 * 场景：defaults、config、scale-healthy、scale-slow、dup、static、dynamic。
 */
public class Rebalance {
    static String bootstrap;
    static long t0;

    static long now() {
        return System.currentTimeMillis() - t0;
    }

    public static void main(String[] args) throws Exception {
        bootstrap = args[0];
        String scenario = args[1];
        String mode = args.length > 2 ? args[2] : "eager";
        switch (scenario) {
            case "defaults" -> defaults();
            case "config" -> config();
            case "scale-healthy" -> scale(mode, false);
            case "scale-slow" -> scale(mode, true);
            case "dup" -> duplicates(mode);
            case "static" -> membership(mode, true);
            case "dynamic" -> membership(mode, false);
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    static void defaults() {
        Map<String, Object> d = ConsumerConfig.configDef().defaultValues();
        System.out.printf("defaults\tkafka-clients 4.3.1 默认：group.protocol=%s，partition.assignment.strategy=%s%n",
                d.get(ConsumerConfig.GROUP_PROTOCOL_CONFIG),
                ((List<?>) d.get(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG)).stream()
                        .map(c -> ((Class<?>) c).getSimpleName()).toList());
    }

    static void config() {
        try (var c = new KafkaConsumer<>(props("cfg", "consumer", Map.of(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000)))) {
            System.out.println("config\t新协议下设置 session.timeout.ms：创建成功");
        } catch (KafkaException e) {
            Throwable t = e instanceof ConfigException ? e : e.getCause();
            System.out.println("config\t新协议下设置 session.timeout.ms：" + t.getClass().getSimpleName() + "：" + t.getMessage());
        }
    }

    static Map<String, Object> props(String group, String mode, Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        switch (mode) {
            case "coop" -> p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
            case "consumer" -> p.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer");
            default -> { }
        }
        p.putAll(extra);
        return p;
    }

    static void createTopic(String name, int partitions) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 3))).all().get();
        }
    }

    static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    /** 一个在独立线程里 poll 的消费者，记录分配变化与每条消息的处理时间。 */
    static class Member implements Runnable {
        final String name;
        final Map<String, Object> props;
        final Set<Integer> owned = ConcurrentHashMap.newKeySet();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger revocations = new AtomicInteger();
        final AtomicInteger assignments = new AtomicInteger();
        final String topic;
        final BiConsumer<Member, ConsumerRecord<String, String>> handler;
        final java.util.function.BiConsumer<Member, KafkaConsumer<String, String>> afterBatch;
        volatile boolean running = true;
        volatile KafkaConsumer<String, String> consumer;
        final Thread thread;

        interface BiConsumer<A, B> {
            void accept(A a, B b) throws Exception;
        }

        Member(String name, String topic, Map<String, Object> props, BiConsumer<Member, ConsumerRecord<String, String>> handler) {
            this(name, topic, props, handler, (m, c) -> { });
        }

        Member(String name, String topic, Map<String, Object> props, BiConsumer<Member, ConsumerRecord<String, String>> handler,
               java.util.function.BiConsumer<Member, KafkaConsumer<String, String>> afterBatch) {
            this.name = name;
            this.topic = topic;
            this.props = props;
            this.handler = handler;
            this.afterBatch = afterBatch;
            this.thread = Thread.ofPlatform().name(name).start(this);
        }

        void event(String s) {
            events.add(String.format("%6d ms  %s %s", now(), name, s));
        }

        @Override
        public void run() {
            try (var c = new KafkaConsumer<String, String>(props)) {
                consumer = c;
                c.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                    public void onPartitionsRevoked(Collection<TopicPartition> ps) {
                        if (ps.isEmpty()) return;
                        revocations.incrementAndGet();
                        ps.forEach(tp -> owned.remove(tp.partition()));
                        event("撤销 " + ids(ps));
                    }

                    public void onPartitionsAssigned(Collection<TopicPartition> ps) {
                        if (ps.isEmpty()) return;
                        assignments.incrementAndGet();
                        ps.forEach(tp -> owned.add(tp.partition()));
                        event("分配 " + ids(ps));
                    }

                    public void onPartitionsLost(Collection<TopicPartition> ps) {
                        ps.forEach(tp -> owned.remove(tp.partition()));
                        event("丢失 " + ids(ps) + "（已被移出组）");
                    }
                });
                while (running) {
                    for (var r : c.poll(Duration.ofMillis(100))) {
                        handler.accept(this, r);
                    }
                    afterBatch.accept(this, c);
                }
            } catch (Exception e) {
                event("退出：" + e.getClass().getSimpleName());
            }
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join();
        }
    }

    static String ids(Collection<TopicPartition> ps) {
        return ps.stream().map(TopicPartition::partition).sorted().toList().toString();
    }

    // ---------- 扩容 ----------

    static void scale(String mode, boolean slow) throws Exception {
        String topic = "scale-" + mode + "-" + (slow ? "slow" : "healthy") + "-" + UUID.randomUUID().toString().substring(0, 6);
        createTopic(topic, 6);
        Map<Integer, List<Long>> seen = new ConcurrentHashMap<>();
        AtomicBoolean producing = new AtomicBoolean(true);
        t0 = System.currentTimeMillis();
        Thread producerThread = Thread.ofPlatform().start(() -> {
            try (var p = producer()) {
                while (producing.get()) {
                    for (int part = 0; part < 6; part++) p.send(new ProducerRecord<>(topic, part, null, "m"));
                    Thread.sleep(100);                          // 每个分区每秒约 10 条
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicLong joinAtRef = new AtomicLong(Long.MAX_VALUE);
        AtomicBoolean slowDone = new AtomicBoolean(!slow);
        AtomicLong slowEnd = new AtomicLong(-1);
        Member.BiConsumer<Member, ConsumerRecord<String, String>> work = (m, r) -> {
            if (m.name.equals("C2") && !slowDone.get() && now() >= joinAtRef.get() - 200) {
                slowDone.set(true);
                m.event("开始一次 8 秒的慢处理");
                Thread.sleep(8000);
                slowEnd.set(now());
                m.event("慢处理结束");
            }
            Thread.sleep(10);
            seen.computeIfAbsent(r.partition(), k -> Collections.synchronizedList(new ArrayList<>())).add(now());
        };
        String group = "g-" + topic;
        Member c1 = new Member("C1", topic, props(group, mode, Map.of()), work);
        Member c2 = new Member("C2", topic, props(group, mode, Map.of()), work);
        // 等两个成员都分到分区、6 个分区都有主，组稳定 5 秒后再扩容（新协议下第二个成员入组要几秒）
        while (owners(c1, c2).size() < 6 || c1.owned.isEmpty() || c2.owned.isEmpty()) Thread.sleep(50);
        long joinAt = Math.max(12_000, now() + 5_000);
        joinAtRef.set(joinAt);
        while (now() < joinAt - 300) Thread.sleep(50);
        Map<Integer, String> before = owners(c1, c2);
        while (now() < joinAt) Thread.sleep(10);
        Member c3 = new Member("C3", topic, props(group, mode, Map.of()), work);
        c3.event("启动");
        while (now() < joinAt + 16_000) Thread.sleep(100);
        Map<Integer, String> after = owners(c1, c2, c3);
        long endAt = now();
        producing.set(false);
        producerThread.join();
        for (Member m : List.of(c1, c2, c3)) m.stop();

        // 每个分区从扩容前 1 秒到结束之间，相邻两条消息处理时间的最大间隔
        Map<Integer, Long> gap = new TreeMap<>();
        Map<Integer, String> gapAt = new TreeMap<>();
        for (var e : seen.entrySet()) {
            List<Long> ts = new ArrayList<>(e.getValue());
            Collections.sort(ts);
            long max = 0, at = -1;
            for (int i = 1; i < ts.size(); i++) {
                long g = ts.get(i) - ts.get(i - 1);
                if (ts.get(i - 1) >= joinAt - 500 && ts.get(i - 1) < endAt && g > max) {
                    max = g;
                    at = ts.get(i - 1);
                }
            }
            gap.put(e.getKey(), max);
            gapAt.put(e.getKey(), at + "→" + (at + max));
        }
        Set<Integer> revokedParts = new TreeSet<>();
        for (Member m : List.of(c1, c2)) {
            for (String s : m.events) {
                long t = Long.parseLong(s.trim().split(" ")[0]);
                if (t >= joinAt && t < endAt && s.contains("撤销")) {
                    String list = s.substring(s.indexOf('[') + 1, s.indexOf(']'));
                    for (String x : list.split(", ")) revokedParts.add(Integer.parseInt(x));
                }
            }
        }
        String key = "scale." + mode + "." + (slow ? "slow" : "healthy");
        System.out.printf("%s\t扩容前 %s，扩容后 %s；被撤销的分区 %d 个 %s；各分区最长中断 %s；超过 300ms 的分区 %d 个%n", key, before, after,
                revokedParts.size(), revokedParts, gap, gap.values().stream().filter(v -> v > 300).count());
        System.out.println(key + ".gaps\t各分区最长中断的起止时间 " + gapAt);
        List<Integer> kept = new ArrayList<>(), movedAll = new ArrayList<>();
        before.forEach((p, o) -> (o.equals(after.get(p)) ? kept : movedAll).add(p));
        System.out.printf("%s.split\t所有者没变的分区 %s 最长中断 %d ms；换了所有者的分区 %s 最长中断 %d ms%n", key,
                kept, kept.stream().mapToLong(gap::get).max().orElse(0), movedAll, movedAll.stream().mapToLong(gap::get).max().orElse(0));
        if (slow) {
            List<Integer> retained = new ArrayList<>(), moved = new ArrayList<>();
            before.forEach((p, o) -> {
                if (o.equals("C1") && "C1".equals(after.get(p))) retained.add(p);
                if (o.equals("C1") && "C3".equals(after.get(p))) moved.add(p);
            });
            long c1Revoke = c1.events.stream().filter(s -> s.contains("撤销")).mapToLong(Rebalance::at)
                    .filter(x -> x >= joinAt && x < endAt).min().orElse(-1);
            long firstAssign = Stream.of(c1, c2, c3).flatMap(m -> m.events.stream()).filter(s -> s.contains("分配"))
                    .mapToLong(Rebalance::at).filter(x -> x >= joinAt && x < endAt).min().orElse(-1);
            long end = slowEnd.get();
            System.out.printf("%s.detail\tC1 保留的分区 %s 最长中断 %s ms；从 C1 移给 C3 的分区 %s 最长中断 %s ms；C2 慢处理结束 %d ms；C1 首次撤销 %d ms（慢处理结束%s）；扩容后首次分配 %d ms（慢处理结束%s）%n",
                    key, retained, retained.stream().map(gap::get).toList(), moved, moved.stream().map(gap::get).toList(), end,
                    c1Revoke, c1Revoke < end ? "之前" : "之后", firstAssign, firstAssign < end ? "之前" : "之后");
        }
        List<String> timeline = new ArrayList<>();
        for (Member m : List.of(c1, c2, c3)) timeline.addAll(m.events);
        timeline.sort(Comparator.comparingLong(s -> Long.parseLong(s.trim().split(" ")[0])));
        timeline.stream().filter(s -> { long t = Long.parseLong(s.trim().split(" ")[0]); return t >= joinAt - 500 && t < endAt; })
                .forEach(s -> System.out.println(key + ".timeline\t" + s));
    }

    static long at(String event) {
        return Long.parseLong(event.trim().split(" ")[0]);
    }

    static Map<Integer, String> owners(Member... ms) {
        Map<Integer, String> o = new TreeMap<>();
        for (Member m : ms) m.owned.forEach(p -> o.put(p, m.name));
        return o;
    }

    // ---------- 处理超时导致的重复 ----------

    static void duplicates(String mode) throws Exception {
        String topic = "dup-" + mode + "-" + UUID.randomUUID().toString().substring(0, 6);
        createTopic(topic, 2);
        try (var p = producer()) {
            for (int i = 0; i < 2000; i++) p.send(new ProducerRecord<>(topic, i % 2, null, "m" + i));
        }
        Map<String, AtomicInteger> processed = new ConcurrentHashMap<>();
        AtomicInteger commitFailures = new AtomicInteger();
        t0 = System.currentTimeMillis();
        Map<String, Object> extra = Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false, ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 5000);
        AtomicInteger c1Batches = new AtomicInteger();
        Map<String, AtomicInteger> batchRecords = new ConcurrentHashMap<>();
        Member.BiConsumer<Member, ConsumerRecord<String, String>> work = (m, r) -> {
            batchRecords.computeIfAbsent(m.name, k -> new AtomicInteger()).incrementAndGet();
            boolean slowBatch = m.name.equals("C1") && c1Batches.get() == 2;
            Thread.sleep(slowBatch ? 80 : 5);                            // C1 的第 3 批每条 80ms，一批 8 秒
            processed.computeIfAbsent(r.partition() + ":" + r.offset(), k -> new AtomicInteger()).incrementAndGet();
        };
        java.util.function.BiConsumer<Member, KafkaConsumer<String, String>> commit = (m, c) -> {
            if (batchRecords.computeIfAbsent(m.name, k -> new AtomicInteger()).getAndSet(0) == 0) return;
            if (m.name.equals("C1")) c1Batches.incrementAndGet();
            try {
                c.commitSync();
            } catch (CommitFailedException e) {
                commitFailures.incrementAndGet();
                m.event("提交失败：CommitFailedException");
            } catch (KafkaException e) {
                commitFailures.incrementAndGet();
                m.event("提交失败：" + e.getClass().getSimpleName());
            }
        };
        Member c1 = new Member("C1", topic, props("g-" + topic, mode, extra), work, commit);
        Member c2 = new Member("C2", topic, props("g-" + topic, mode, extra), work, commit);
        long idleSince = System.currentTimeMillis();
        int last = -1;
        while (System.currentTimeMillis() - idleSince < 5000 || processed.size() < 2000) {
            Thread.sleep(200);
            int total = processed.values().stream().mapToInt(AtomicInteger::get).sum();
            if (total != last) {
                last = total;
                idleSince = System.currentTimeMillis();
            }
            if (now() > 90_000) break;
        }
        c1.stop();
        c2.stop();
        long twice = processed.values().stream().filter(a -> a.get() > 1).count();
        System.out.printf("dup.%s\t共 2000 条，处理过的不同消息 %d 条，其中 %d 条被处理了两次或以上；提交失败 %d 次%n",
                mode, processed.size(), twice, commitFailures.get());
        List<String> timeline = new ArrayList<>(c1.events);
        timeline.addAll(c2.events);
        timeline.sort(Comparator.comparingLong(s -> Long.parseLong(s.trim().split(" ")[0])));
        timeline.forEach(s -> System.out.println("dup." + mode + ".timeline\t" + s));
    }

    // ---------- 静态成员身份 ----------

    static void membership(String mode, boolean isStatic) throws Exception {
        String topic = "member-" + mode + "-" + (isStatic ? "static" : "dynamic") + "-" + UUID.randomUUID().toString().substring(0, 6);
        createTopic(topic, 4);
        t0 = System.currentTimeMillis();
        Member.BiConsumer<Member, ConsumerRecord<String, String>> noop = (m, r) -> { };
        String group = "g-" + topic;
        Map<String, Object> e1 = isStatic ? Map.of(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "c1") : Map.of();
        Map<String, Object> e2 = isStatic ? Map.of(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "c2") : Map.of();
        Member c1 = new Member("C1", topic, props(group, mode, e1), noop);
        Member c2 = new Member("C2", topic, props(group, mode, e2), noop);
        while (c1.owned.isEmpty() || c2.owned.isEmpty() || c1.owned.size() + c2.owned.size() < 4) Thread.sleep(50);
        long closeAt = now() + 3_000;
        while (now() < closeAt) Thread.sleep(100);
        Set<Integer> c1Before = new TreeSet<>(c1.owned);
        int r0 = c2.revocations.get(), a0 = c2.assignments.get();
        c1.event("关闭");
        c1.stop();
        while (now() < closeAt + 3_000) Thread.sleep(100);
        long restartAt = now();
        Member c1b = new Member("C1", topic, props(group, mode, e1), noop);
        c1b.event("用" + (isStatic ? "同一个 group.instance.id" : "新的成员身份") + "重启");
        long regained = -1;
        while (now() < restartAt + 22_000) {
            if (regained < 0 && !c1b.owned.isEmpty()) regained = now() - restartAt;
            Thread.sleep(50);
        }
        String key = "member." + mode + "." + (isStatic ? "static" : "dynamic");
        System.out.printf("%s\tC1 重启前持有 %s；C1 关闭、3 秒后重启、再观察 22 秒，C2 被撤销 %d 次、分配 %d 次；C1 重启后 %s 拿回 %s%n", key, c1Before,
                c2.revocations.get() - r0, c2.assignments.get() - a0, regained < 0 ? "没有" : regained + " ms", new TreeSet<>(c1b.owned));
        c1b.stop();
        c2.stop();
    }
}
