package labs.kafka;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 所有测试共用一个 Kafka 4.3.1 容器（固定 digest）。 */
abstract class KafkaBase {
    static final String IMAGE = "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }
}
