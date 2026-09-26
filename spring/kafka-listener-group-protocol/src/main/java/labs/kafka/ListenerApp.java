package labs.kafka;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 一个最普通的 @KafkaListener，消费者配置全部来自 Spring Boot 默认值与 application 配置项。 */
@SpringBootApplication
public class ListenerApp {

    @Component
    public static class Orders {
        public final List<String> received = new CopyOnWriteArrayList<>();

        @KafkaListener(id = "orders", topics = "orders", groupId = "orders")
        public void on(String value) {
            received.add(value);
        }

        public List<String> received() {
            return received;
        }
    }
}
