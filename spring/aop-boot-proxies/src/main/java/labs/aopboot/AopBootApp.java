package labs.aopboot;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** 缓存与事务切面的开启方式由 Ordering 按配置项选择，其余全部使用 Spring Boot 默认值。 */
@SpringBootApplication
@EnableAsync
public class AopBootApp {
}
