import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import java.util.*;
import java.util.concurrent.*;

public class KafkaSlice {
    public static void main(String[] a) throws Exception {
        String topic = "slice-" + System.nanoTime();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", "localhost:9092"))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        }
        Properties p = new Properties();
        p.put("bootstrap.servers", "localhost:9092");
        p.put("key.serializer", StringSerializer.class.getName()); p.put("value.serializer", StringSerializer.class.getName());
        p.put("linger.ms", "500");                       // 最多等 500ms 攒批
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(topic, "warmup", "x")).get();   // 预热：拿到元数据
            long t0 = System.nanoTime();
            List<Future<RecordMetadata>> fs = new ArrayList<>();
            CountDownLatch acked = new CountDownLatch(300);
            long[] firstAck = {0};
            for (int i = 0; i < 300; i++) {
                fs.add(producer.send(new ProducerRecord<>(topic, "k" + i, "v" + i), (md, ex) -> {
                    synchronized (firstAck) { if (firstAck[0] == 0) firstAck[0] = System.nanoTime(); }
                    acked.countDown();
                }));
            }
            long sendDone = System.nanoTime();
            long notDone = fs.stream().filter(f -> !f.isDone()).count();
            double reqBefore = metric(producer, "request-total");
            acked.await();
            System.out.printf("300 次 send() 全部返回用时 %.1f ms，此时未完成的 Future %d 个%n", (sendDone - t0) / 1e6, notDone);
            System.out.printf("第一个回调在第 %.0f ms 到达（linger.ms = 500）%n", (firstAck[0] - t0) / 1e6);
            System.out.printf("这 300 条消息产生的生产请求：%.0f 个，平均每批 %.0f 条%n",
                    metric(producer, "request-total") - reqBefore, metric(producer, "records-per-request-avg"));

            long t1 = System.nanoTime();
            Future<RecordMetadata> f = producer.send(new ProducerRecord<>(topic, "k", "v"));
            producer.flush();
            System.out.printf("send() 后立即 flush()：%.1f ms 内完成，isDone = %s%n", (System.nanoTime() - t1) / 1e6, f.isDone());
        }
    }
    static double metric(KafkaProducer<?, ?> p, String name) {
        return p.metrics().entrySet().stream().filter(e -> e.getKey().name().equals(name) && e.getKey().group().equals("producer-metrics"))
                .mapToDouble(e -> ((Number) e.getValue().metricValue()).doubleValue()).findFirst().orElse(-1);
    }
}
