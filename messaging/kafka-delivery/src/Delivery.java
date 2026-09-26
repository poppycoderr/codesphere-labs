import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.errors.*;
import org.apache.kafka.common.serialization.*;

/**
 * Kafka 投递语义的几个边界，输出为「键<TAB>事实」。第一个参数是 bootstrap，第二个是场景：
 * tx：读—处理—写事务中止与提交；fence：同一 transactional.id 的新实例隔离旧实例；
 * autocommit：自动提交 + 异步处理会提交还没处理完的 offset；
 * isr-setup / isr-full / isr-shrunk：min.insync.replicas 与 ISR 收缩（由脚本在两次运行之间停掉两个 broker）。
 */
public class Delivery {
    static String bootstrap;

    public static void main(String[] args) throws Exception {
        bootstrap = args[0];
        switch (args[1]) {
            case "tx" -> transactions();
            case "fence" -> fencing();
            case "autocommit" -> autoCommit();
            case "isr-setup" -> isrSetup();
            case "isr-full" -> isrSend("isr-full");
            case "isr-shrunk" -> isrShrunk();
            default -> throw new IllegalArgumentException(args[1]);
        }
    }

    static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
    }

    static void createTopics(NewTopic... topics) throws Exception {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(topics)).all().get();
        }
    }

    static KafkaProducer<String, String> producer(Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        p.putAll(extra);
        return new KafkaProducer<>(p);
    }

    static KafkaConsumer<String, String> consumer(String group, Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        p.putAll(extra);
        return new KafkaConsumer<>(p);
    }

    /** 从头读完一个 topic，返回条数。 */
    static int countAll(String topic, String isolation) {
        try (var c = consumer("count-" + UUID.randomUUID(), Map.of(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            c.subscribe(List.of(topic));
            int n = 0;
            long idleSince = System.currentTimeMillis();
            while (System.currentTimeMillis() - idleSince < 3000) {
                int got = c.poll(Duration.ofMillis(200)).count();
                if (got > 0) {
                    n += got;
                    idleSince = System.currentTimeMillis();
                }
            }
            return n;
        }
    }

    static Long committed(String group, String topic) throws Exception {
        try (Admin admin = admin()) {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata o = offsets.get(new TopicPartition(topic, 0));
            return o == null ? null : o.offset();
        }
    }

    /** 读—处理—写：第一轮在提交前抛异常并中止，第二轮正常提交。 */
    static void transactions() throws Exception {
        createTopics(new NewTopic("tx-in", 1, (short) 3), new NewTopic("tx-out", 1, (short) 3));
        try (var p = producer(Map.of(ProducerConfig.ACKS_CONFIG, "all"))) {
            for (int i = 0; i < 10; i++) p.send(new ProducerRecord<>("tx-in", "order-" + i, "created-" + i));
            p.flush();
        }
        for (boolean fail : new boolean[] {true, false}) {
            try (var c = consumer("enricher", Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"));
                 var p = producer(Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "enricher-0"))) {
                c.subscribe(List.of("tx-in"));
                p.initTransactions();
                ConsumerRecords<String, String> records = ConsumerRecords.empty();
                while (records.count() < 10) {
                    var more = c.poll(Duration.ofMillis(500));
                    if (!more.isEmpty()) records = more;
                }
                p.beginTransaction();
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                for (var r : records) {
                    p.send(new ProducerRecord<>("tx-out", r.key(), r.value() + "-enriched"));
                    offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                }
                p.sendOffsetsToTransaction(offsets, c.groupMetadata());
                p.flush();                                       // 消息已经写进 tx-out 的日志
                if (fail) {
                    p.abortTransaction();                        // 模拟处理到一半失败
                    System.out.printf("tx.abort\t中止事务后：read_committed 读到 %d 条，read_uncommitted 读到 %d 条，tx-in 的已提交 offset %s%n",
                            countAll("tx-out", "read_committed"), countAll("tx-out", "read_uncommitted"), committed("enricher", "tx-in"));
                } else {
                    p.commitTransaction();
                    System.out.printf("tx.commit\t重新处理并提交后：read_committed 读到 %d 条，tx-in 的已提交 offset %s%n",
                            countAll("tx-out", "read_committed"), committed("enricher", "tx-in"));
                }
            }
        }
    }

    /** 两个实例使用同一个 transactional.id，后初始化的实例把前一个隔离；旧实例分别继续写入、直接提交。 */
    static void fencing() throws Exception {
        createTopics(new NewTopic("fence", 1, (short) 3));
        for (String action : new String[] {"send", "commit"}) {
            String id = "fence-" + action;
            try (var old = producer(Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, id));
                 var fresh = producer(Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, id))) {
                old.initTransactions();
                old.beginTransaction();
                old.send(new ProducerRecord<>("fence", id, "from-old")).get();
                fresh.initTransactions();                              // 新实例启动：旧实例未完成的事务被中止
                String result;
                try {
                    if (action.equals("send")) {
                        old.send(new ProducerRecord<>("fence", id, "from-old-again")).get();
                    }
                    old.commitTransaction();
                    result = "提交成功";
                } catch (ExecutionException e) {
                    result = e.getCause().getClass().getSimpleName();
                } catch (KafkaException e) {
                    result = e.getClass().getSimpleName();
                }
                String abort;
                try {
                    old.abortTransaction();
                    abort = "abortTransaction 成功";
                } catch (KafkaException e) {
                    abort = "abortTransaction 抛出 " + e.getClass().getSimpleName();
                }
                System.out.printf("fence.%s	新实例 initTransactions 之后，旧实例%s：%s；随后 %s%n",
                        action, action.equals("send") ? "继续发送" : "直接提交", result, abort);
            }
        }
        try (var fresh = producer(Map.of(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "fence-commit"))) {
            fresh.initTransactions();
            fresh.beginTransaction();
            fresh.send(new ProducerRecord<>("fence", "fence-commit", "from-fresh")).get();
            fresh.commitTransaction();
        }
        System.out.printf("fence.visible	read_committed 读到 %d 条：旧实例写入的消息都随事务中止%n", countAll("fence", "read_committed"));
    }

    /** 自动提交 + 把消息交给线程池异步处理：offset 在处理完之前就提交了。 */
    static void autoCommit() throws Exception {
        createTopics(new NewTopic("async", 1, (short) 3));
        try (var p = producer(Map.of())) {
            for (int i = 0; i < 100; i++) p.send(new ProducerRecord<>("async", "k" + i, "v" + i));
            p.flush();
        }
        AtomicInteger processed = new AtomicInteger();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (var c = consumer("async-auto", Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true,
                ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 100))) {
            c.subscribe(List.of("async"));
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                for (var r : c.poll(Duration.ofMillis(100))) {
                    workers.submit(() -> {
                        try {
                            Thread.sleep(50);                  // 每条处理 50ms
                            processed.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }
            workers.shutdownNow();                             // 模拟进程在处理中途退出
        }
        Long offset = committed("async-auto", "async");
        System.out.printf("autocommit\t自动提交 + 异步处理，2 秒后退出：已处理 %d 条，已提交 offset %d，重启后有 %d 条不会再被消费%n",
                processed.get(), offset, offset - processed.get());
    }

    static void isrSetup() throws Exception {
        // 副本固定在 1、2、3 号 broker，leader 是 1 号；脚本随后停掉 2、3 号
        createTopics(new NewTopic("isr-strict", Map.of(0, List.of(1, 2, 3))).configs(Map.of("min.insync.replicas", "2")),
                new NewTopic("isr-default", Map.of(0, List.of(1, 2, 3))));
        System.out.println("isr.setup\tisr-strict：3 副本、min.insync.replicas=2；isr-default：3 副本、min.insync.replicas=1（默认）");
    }

    static List<Integer> isr(String topic) throws Exception {
        try (Admin admin = admin()) {
            return admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions().get(0)
                    .isr().stream().map(Node::id).sorted().toList();
        }
    }

    static String send(String topic, String acks) {
        return send(topic, acks, Map.of());
    }

    /** 投递超时设为 5 秒；retries=0 时直接看到服务端返回的错误，否则可重试的错误会一直重试到超时。 */
    static String send(String topic, String acks, Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(Map.of(ProducerConfig.ACKS_CONFIG, acks, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, acks.equals("all"),
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                ProducerConfig.LINGER_MS_CONFIG, 0));
        cfg.putAll(extra);
        try (var p = producer(cfg)) {
            p.send(new ProducerRecord<>(topic, "k", "v")).get();
            return "成功";
        } catch (ExecutionException e) {
            return e.getCause().getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    static void isrSend(String key) throws Exception {
        System.out.printf("%s\tISR %s：isr-strict acks=all %s%n", key, isr("isr-strict"), send("isr-strict", "all"));
    }

    static void isrShrunk() throws Exception {
        long t0 = System.currentTimeMillis();
        while (isr("isr-strict").size() > 1 || isr("isr-default").size() > 1) {
            if (System.currentTimeMillis() - t0 > 60_000) throw new IllegalStateException("ISR did not shrink");
            Thread.sleep(500);
        }
        long s0 = System.currentTimeMillis();
        String retried = send("isr-strict", "all");
        long retriedMs = System.currentTimeMillis() - s0;
        System.out.printf("isr-shrunk.strict\tISR %s：isr-strict acks=all 默认重试 %s（%s），retries=0 %s；acks=1 %s%n",
                isr("isr-strict"), retried, retriedMs >= 5000 ? "等满 delivery.timeout.ms=5000 才失败" : retriedMs + "ms",
                send("isr-strict", "all", Map.of(ProducerConfig.RETRIES_CONFIG, 0, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false)),
                send("isr-strict", "1"));
        System.out.printf("isr-shrunk.default\tISR %s：isr-default（min.insync.replicas=1）acks=all %s%n",
                isr("isr-default"), send("isr-default", "all"));
    }
}
