package labs.kafka;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;

/** 不做任何配置时 @KafkaListener 使用的协议与分配器：从 Kafka 客户端启动时打印的 ConsumerConfig 读取。 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ListenerDefaultsTest extends KafkaBase {
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ListenerApp.Orders orders;

    @Test
    void defaultsAreClassicAndRange(CapturedOutput output) throws Exception {
        template.send("orders", "o-1").get();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (orders.received().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue(orders.received().contains("o-1"));
        String cfg = consumerConfig(output.getAll());
        Facts.record("defaults", "@KafkaListener 默认：" + value(cfg, "group.protocol") + "，" + value(cfg, "partition.assignment.strategy")
                + "，收到消息=" + orders.received().contains("o-1"));
    }

    static String consumerConfig(String log) {
        int i = log.indexOf("ConsumerConfig values:");
        return log.substring(i, log.indexOf("\n\n", i) > 0 ? log.indexOf("\n\n", i) : log.length());
    }

    static String value(String cfg, String key) {
        Matcher m = Pattern.compile("\\t" + Pattern.quote(key) + " = ([^\\n]*)").matcher(cfg);
        return m.find() ? key + " = " + m.group(1).trim() : key + " 未打印";
    }
}
