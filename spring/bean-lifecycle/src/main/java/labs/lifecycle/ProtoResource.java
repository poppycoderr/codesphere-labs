package labs.lifecycle;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/** prototype 作用域：容器创建并初始化它，但不负责销毁。 */
public class ProtoResource {
    @PostConstruct
    void open() {
        Journal.add("ProtoResource @PostConstruct");
    }

    @PreDestroy
    void close() {
        Journal.add("ProtoResource @PreDestroy");
    }
}
