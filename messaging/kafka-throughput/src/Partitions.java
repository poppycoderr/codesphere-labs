import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;

/**
 * 分区与并行，输出为「键<TAB>事实」：
 * 1. 同样 3000 条、每条处理 2ms，6 个消费者分别消费 1 个分区和 6 个分区的 topic 需要多久；
 * 2. 80% 的消息使用同一个 key 时，6 个分区各分到多少，消费耗时被哪个分区决定。
 */
public class Partitions {
    static String bootstrap;

    public static void main(String[] args) throws Exception {
        bootstrap = args[0];
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new NewTopic("par-1", 1, (short) 1), new NewTopic("par-6", 6, (short) 1),
                    new NewTopic("even-6", 6, (short) 1), new NewTopic("hot-6", 6, (short) 1))).all().get();
        }
        parallelism("par-1");
        parallelism("par-6");
        skew("even-6", 0.0);
        skew("hot-6", 0.8);
    }

    static void parallelism(String topic) throws Exception {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 3000; i++) keys.add("order-" + i);
        Result r = consumeWhileProducing(topic, keys, 2);
        System.out.printf("parallel.%s\t%s：3000 条、每条处理 2ms、6 个消费者：从第一条到最后一条 %.1f 秒，真正处理过消息的消费者 %d 个%n",
                topic, topic.equals("par-1") ? "1 个分区" : "6 个分区", r.seconds, r.busyConsumers);
    }

    static void skew(String topic, double hotShare) throws Exception {
        Random random = new Random(7);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 6000; i++) keys.add(random.nextDouble() < hotShare ? "event-42" : "event-" + random.nextInt(100_000));
        int[][] holder = new int[1][];
        Result r = consumeWhileProducing(topic, keys, 1, holder);
        int[] perPartition = holder[0];
        int max = Arrays.stream(perPartition).max().orElseThrow();
        System.out.printf("skew.%s\t%s：6000 条分到 6 个分区 %s，最大分区占 %.0f%%；每条处理 1ms、6 个消费者：%.1f 秒%n",
                topic, hotShare == 0 ? "key 均匀分布" : "80% 的消息 key 都是 event-42", Arrays.toString(perPartition), 100.0 * max / 6000, r.seconds);
    }

    static int[] produce(String topic, List<String> keys) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            int partitions = producer.partitionsFor(topic).size();
            int[] counts = new int[partitions];
            List<Future<RecordMetadata>> fs = new ArrayList<>();
            for (String k : keys) fs.add(producer.send(new ProducerRecord<>(topic, k, "payload")));
            for (Future<RecordMetadata> f : fs) counts[f.get().partition()]++;
            return counts;
        }
    }

    record Result(double seconds, int busyConsumers) {}

    static Result consumeWhileProducing(String topic, List<String> keys, long millisPerRecord) throws Exception {
        return consumeWhileProducing(topic, keys, millisPerRecord, new int[1][]);
    }

    /** 先让 6 个消费者都加入消费组并分到分区，再开始生产，测量从第一条被处理到最后一条被处理的时间。 */
    static Result consumeWhileProducing(String topic, List<String> keys, long millisPerRecord, int[][] perPartition) throws Exception {
        int expected = keys.size();
        AtomicInteger assigned = new AtomicInteger(), members = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicLong first = new AtomicLong(), last = new AtomicLong();
        Set<Integer> busy = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        String group = "g-" + topic + "-" + System.nanoTime();
        for (int c = 0; c < 6; c++) {
            int id = c;
            pool.submit(() -> {
                Properties p = new Properties();
                p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
                p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
                try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
                    consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                        @Override public void onPartitionsRevoked(Collection<org.apache.kafka.common.TopicPartition> parts) { assigned.addAndGet(-parts.size()); }
                        @Override public void onPartitionsAssigned(Collection<org.apache.kafka.common.TopicPartition> parts) { assigned.addAndGet(parts.size()); }
                    });
                    members.incrementAndGet();
                    while (done.get() < expected) {
                        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                            first.compareAndSet(0, System.nanoTime());
                            Thread.sleep(millisPerRecord);
                            busy.add(id);
                            done.incrementAndGet();
                            last.set(System.nanoTime());
                        }
                    }
                }
                return null;
            });
        }
        int partitions = topic.equals("par-1") ? 1 : 6;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        int stable = 0;
        while (stable < 10) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("消费组没有稳定");
            Thread.sleep(200);
            stable = (members.get() == 6 && assigned.get() == partitions) ? stable + 1 : 0;
        }
        perPartition[0] = produce(topic, keys);
        pool.shutdown();
        if (!pool.awaitTermination(3, TimeUnit.MINUTES)) throw new IllegalStateException("消费超时");
        return new Result((last.get() - first.get()) / 1e9, busy.size());
    }
}
