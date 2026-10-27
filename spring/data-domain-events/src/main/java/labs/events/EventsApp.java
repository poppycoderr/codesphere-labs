package labs.events;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 实验用的 Spring Boot 应用：Spring Data JPA + H2；仓储接口写在一个类里，需要打开嵌套扫描。 */
@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true)
public class EventsApp {
}
