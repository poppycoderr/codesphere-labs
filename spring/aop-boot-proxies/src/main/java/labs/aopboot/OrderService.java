package labs.aopboot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 实现了接口的事务服务：纯 Spring 默认是 JDK 代理，Spring Boot 默认是 CGLIB 代理。 */
@Service
public class OrderService implements OrderApi {
    @Override
    @Transactional
    public String place(long id) {
        return "order-" + id;
    }
}
