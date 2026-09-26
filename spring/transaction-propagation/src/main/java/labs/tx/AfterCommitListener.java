package labs.tx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 只在事务提交后收到事件。 */
@Component
public class AfterCommitListener {
    public final List<Long> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(CheckoutService.OrderCreated event) {
        received.add(event.orderId());
    }
}
