package labs.kafka;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;

/** 只加一个配置项 group.protocol=consumer，容器能否照常消费。 */
@SpringBootTest(properties = "spring.kafka.consumer.properties[group.protocol]=consumer")
@ExtendWith(OutputCaptureExtension.class)
class ListenerNewProtocolTest extends KafkaBase {
    @Autowired KafkaTemplate<String, String> template;
    @Autowired ListenerApp.Orders orders;

    @Test
    void newProtocolWorksWithOneProperty(CapturedOutput output) throws Exception {
        template.send("orders", "o-2").get();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (!orders.received().contains("o-2") && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertTrue(orders.received().contains("o-2"));
        String cfg = ListenerDefaultsTest.consumerConfig(output.getAll());
        Facts.record("consumer", "只设置 group.protocol=consumer：" + ListenerDefaultsTest.value(cfg, "group.protocol") + "，"
                + ListenerDefaultsTest.value(cfg, "group.remote.assignor") + "，收到消息=" + orders.received().contains("o-2"));
    }
}
