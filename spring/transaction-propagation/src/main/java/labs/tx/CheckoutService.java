package labs.tx;

import java.util.concurrent.CyclicBarrier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 外层事务里调用 REQUIRES_NEW、发布事务事件、抛出受检异常的几个场景。 */
@Service
public class CheckoutService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public CheckoutService(JdbcTemplate jdbc, AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    public record OrderCreated(long orderId) {
    }

    public static class PaymentRejected extends Exception {
        public PaymentRejected() {
            super("payment rejected");
        }
    }

    /** 审计独立提交，之后订单因支付失败回滚。 */
    @Transactional
    public void placeOrderWithAudit(long orderId) {
        jdbc.update("INSERT INTO orders VALUES (?, 'audited')", orderId);
        audit.record("ORDER_CREATED " + orderId);
        throw new IllegalStateException("freeze failed");
    }

    /** 两个线程同时进入外层事务、各占一个连接后，再调用 REQUIRES_NEW。 */
    @Transactional
    public void holdConnectionThenAudit(CyclicBarrier bothInside, String msg) throws Exception {
        jdbc.queryForObject("SELECT 1", Integer.class);
        bothInside.await();
        audit.record(msg);
    }

    /** 外层锁住账户行，内层独立事务更新同一行。 */
    @Transactional
    public void lockThenAdjustInNewTransaction(long accountId) {
        jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ? FOR UPDATE", Integer.class, accountId);
        audit.adjustBalance(accountId, 1);
    }

    @Transactional
    public void confirmWithCheckedFailure(long orderId) throws PaymentRejected {
        jdbc.update("INSERT INTO orders VALUES (?, 'checked')", orderId);
        throw new PaymentRejected();
    }

    @Transactional
    public void placeOrderAndPublish(long orderId, boolean fail) {
        jdbc.update("INSERT INTO orders VALUES (?, 'event')", orderId);
        events.publishEvent(new OrderCreated(orderId));
        if (fail) {
            throw new IllegalStateException("rollback after publish");
        }
    }
}
