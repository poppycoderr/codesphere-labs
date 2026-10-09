import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;

/**
 * 按业务键保序在哪些地方会断：分区数变化、同一个分区里并行处理、失败后延迟重试、不同客户端的分区算法。
 * 消息的值形如 "order-7:v3"，表示订单 7 的第 3 个版本；「状态」是一个进程内的 Map，保存每个订单最后一次被应用的版本。
 */
public class Ordering {
    static String boot;
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static Properties producerProps() {
        Properties p = new Properties();
        p.put("bootstrap.servers", boot);
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        return p;
    }

    static KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put("bootstrap.servers", boot);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "earliest");
        return new KafkaConsumer<>(p);
    }

    static int send(KafkaProducer<String, String> producer, String topic, String key, String value) throws Exception {
        return producer.send(new ProducerRecord<>(topic, key, value)).get().partition();
    }

    /** 把一个分区从头读到尾 */
    static List<ConsumerRecord<String, String>> readAll(String topic, int partition) {
        try (KafkaConsumer<String, String> c = consumer()) {
            TopicPartition tp = new TopicPartition(topic, partition);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            long end = c.endOffsets(List.of(tp)).get(tp);
            List<ConsumerRecord<String, String>> all = new ArrayList<>();
            while (c.position(tp) < end) c.poll(Duration.ofMillis(500)).forEach(all::add);
            return all;
        }
    }

    static int version(String value) { return Integer.parseInt(value.substring(value.indexOf(":v") + 2)); }

    public static void main(String[] args) throws Exception {
        boot = args[0];
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", boot)); KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            // ---------- 一、分区数从 4 改成 8 ----------
            admin.createTopics(List.of(new NewTopic("orders", 4, (short) 1))).all().get();
            Map<String, Integer> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            for (int i = 1; i <= 20; i++) {
                String key = "order-" + i;
                before.put(key, send(producer, "orders", key, key + ":v1"));
                send(producer, "orders", key, key + ":v2");
            }
            admin.createPartitions(Map.of("orders", NewPartitions.increaseTo(8))).all().get();
            Thread.sleep(1500);                                      // 等生产者的元数据刷新
            try (KafkaProducer<String, String> fresh = new KafkaProducer<>(producerProps())) {
                for (int i = 1; i <= 20; i++) { String key = "order-" + i; after.put(key, send(fresh, "orders", key, key + ":v3")); }
            }
            List<String> moved = new ArrayList<>();
            for (String k : before.keySet()) if (!before.get(k).equals(after.get(k))) moved.add(k + "（" + before.get(k) + "→" + after.get(k) + "）");
            out("resize.moved", "20 个订单里有 " + moved.size() + " 个在扩容后换了分区：" + String.join("、", moved));
            // 取一个换了分区的订单：v1、v2 还在旧分区，v3 在新分区。新分区没有积压，先被消费
            String victim = moved.get(0).substring(0, moved.get(0).indexOf('（'));
            List<String> order = new ArrayList<>();
            for (int partition : new int[]{after.get(victim), before.get(victim)})
                for (ConsumerRecord<String, String> r : readAll("orders", partition)) if (r.key().equals(victim)) order.add(r.value() + "@分区" + partition);
            out("resize.interleave", victim + " 的三个版本分布在两个分区；新分区先被消费时的处理顺序：" + String.join(" → ", order)
                    + "；按到达顺序覆盖，最后留下的是 v" + version(order.get(order.size() - 1).split("@")[0]));
            boolean inOrder = true;
            for (int p = 0; p < 8; p++) {
                Map<String, Integer> last = new TreeMap<>();
                for (ConsumerRecord<String, String> r : readAll("orders", p)) { if (last.getOrDefault(r.key(), 0) > version(r.value())) inOrder = false; last.put(r.key(), version(r.value())); }
            }
            out("resize.within_partition", "每个分区内部，同一个订单的版本都是递增的 = " + inOrder);

            // ---------- 二、同一个分区里并行处理 ----------
            admin.createTopics(List.of(new NewTopic("payments", 1, (short) 1))).all().get();
            for (int v = 1; v <= 6; v++) send(producer, "payments", "order-1", "order-1:v" + v);
            for (int v = 1; v <= 6; v++) send(producer, "payments", "order-2", "order-2:v" + v);
            List<ConsumerRecord<String, String>> batch = readAll("payments", 0);
            Map<String, Integer> state = new ConcurrentHashMap<>();
            List<String> applied = Collections.synchronizedList(new ArrayList<>());
            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<?>> fs = new ArrayList<>();
                for (ConsumerRecord<String, String> r : batch) fs.add(pool.submit(() -> {
                    try { Thread.sleep(version(r.value()) % 2 == 1 ? 120 : 10); } catch (InterruptedException ignored) { }   // 奇数版本处理得慢
                    state.put(r.key(), version(r.value())); applied.add(r.value());
                }));
                for (Future<?> f : fs) f.get();
            }
            out("parallel.pool", "同一个分区的 12 条消息交给 4 个线程的池：order-1 最后留下 v" + state.get("order-1") + "，order-2 最后留下 v" + state.get("order-2") + "（日志里最后一条都是 v6）");
            Map<String, Integer> state2 = new ConcurrentHashMap<>();
            ExecutorService[] lanes = {Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()};
            List<Future<?>> fs2 = new ArrayList<>();
            long t0 = System.nanoTime();
            for (ConsumerRecord<String, String> r : batch) fs2.add(lanes[Math.floorMod(r.key().hashCode(), lanes.length)].submit(() -> {
                try { Thread.sleep(version(r.value()) % 2 == 1 ? 120 : 10); } catch (InterruptedException ignored) { }
                state2.put(r.key(), version(r.value()));
            }));
            for (Future<?> f : fs2) f.get();
            long laneMs = (System.nanoTime() - t0) / 1_000_000;
            for (ExecutorService l : lanes) l.shutdown();
            out("parallel.lanes", "按订单号把消息分到 4 条各自串行的通道：order-1 最后留下 v" + state2.get("order-1") + "，order-2 最后留下 v" + state2.get("order-2")
                    + "；两个订单是否并行处理 = " + (laneMs < 700));

            // ---------- 三、失败的消息延迟重试 ----------
            admin.createTopics(List.of(new NewTopic("stock", 1, (short) 1), new NewTopic("stock.retry", 1, (short) 1))).all().get();
            send(producer, "stock", "sku-1", "sku-1:v1");
            send(producer, "stock", "sku-1", "sku-1:v2");
            Map<String, Integer> blind = new LinkedHashMap<>(), versioned = new LinkedHashMap<>();
            List<String> log = new ArrayList<>();
            for (ConsumerRecord<String, String> r : readAll("stock", 0)) {
                if (version(r.value()) == 1) { send(producer, "stock.retry", r.key(), r.value()); log.add("v1 处理失败，转入重试主题"); continue; }
                blind.put(r.key(), version(r.value())); versioned.put(r.key(), version(r.value())); log.add("v" + version(r.value()) + " 处理成功");
            }
            for (ConsumerRecord<String, String> r : readAll("stock.retry", 0)) {
                blind.put(r.key(), version(r.value()));
                boolean accepted = versioned.merge(r.key(), version(r.value()), Math::max) == version(r.value());
                log.add("重试 v" + version(r.value()) + (accepted ? "，被接受" : "，按版本比较被丢弃"));
            }
            out("retry.sequence", String.join("；", log));
            out("retry.final", "直接覆盖：sku-1 最后是 v" + blind.get("sku-1") + "；只接受更大的版本：sku-1 最后是 v" + versioned.get("sku-1"));

            // ---------- 四、不同的分区算法 ----------
            int differ = 0;
            for (int i = 1; i <= 20; i++) {
                byte[] k = ("order-" + i).getBytes(StandardCharsets.UTF_8);
                int murmur = Utils.toPositive(Utils.murmur2(k)) % 8;
                CRC32 crc = new CRC32(); crc.update(k);
                if (murmur != (int) (crc.getValue() % 8)) differ++;
                if (murmur != after.get("order-" + i)) throw new IllegalStateException("murmur2 的计算与 broker 上的实际分区不一致");
            }
            out("partitioner", "8 个分区下，20 个订单号按 murmur2 取模（Java 客户端默认）与按 CRC32 取模得到不同分区的有 " + differ + " 个；murmur2 的计算与实际写入的分区全部一致");
        }
    }
}
