package labs.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 新协议下仍保留旧的会话超时配置：应用启动失败，找出根因。 */
class ListenerNewProtocolConflictTest extends KafkaBase {

    @Test
    void sessionTimeoutIsRejected() {
        String result;
        try (var ctx = new SpringApplicationBuilder(ListenerApp.class).run(
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.properties.group.protocol=consumer",
                "--spring.kafka.consumer.properties.session.timeout.ms=30000")) {
            result = "应用启动成功";
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null) t = t.getCause();
            result = "应用启动失败，根因 " + t.getClass().getSimpleName() + "：" + t.getMessage();
        }
        Facts.record("conflict", "group.protocol=consumer 且保留 session.timeout.ms：" + result);
    }
}
