package labs.async;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** 只开启 @EnableAsync，不自定义任何 Executor，观察 Spring Boot 自动配置的默认执行器。 */
@SpringBootApplication
@EnableAsync
public class AsyncApp {
}
