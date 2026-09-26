package labs.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下单：外层 REQUIRED 事务，调用加积分并吞掉异常。 */
@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    private final PointService points;

    public OrderService(JdbcTemplate jdbc, PointService points) {
        this.jdbc = jdbc;
        this.points = points;
    }

    @Transactional
    public void placeOrderSwallowing(long orderId) {
        jdbc.update("INSERT INTO orders VALUES (?, 'swallow')", orderId);
        try {
            points.add(42, 10);
        } catch (RuntimeException e) {
            // 以为吞掉异常就能继续提交
        }
    }

    @Transactional
    public void placeOrderLenient(long orderId) {
        jdbc.update("INSERT INTO orders VALUES (?, 'lenient')", orderId);
        try {
            points.addLenient(42, 10);
        } catch (RuntimeException e) {
            // 内层声明了 noRollbackFor，不会把共享事务标记为 rollback-only
        }
    }
}
