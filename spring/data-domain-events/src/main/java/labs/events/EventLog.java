package labs.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 记录收到的事件：普通监听器在发布时同步执行，提交后监听器在事务提交后执行。 */
@Component
public class EventLog {
    public final List<String> sync = new CopyOnWriteArrayList<>();
    public final List<String> afterCommit = new CopyOnWriteArrayList<>();

    @EventListener
    void onAny(Object event) {
        if (event.getClass().getEnclosingClass() == Events.class) {
            sync.add(event.getClass().getSimpleName() + (TransactionSynchronizationManager.isActualTransactionActive() ? "（事务内）" : "（无事务）"));
        }
    }

    @TransactionalEventListener
    void onCommitted(Events.TicketClosed e) {
        afterCommit.add(e.getClass().getSimpleName());
    }

    public void clear() {
        sync.clear();
        afterCommit.clear();
    }

    public long count(String type) {
        return sync.stream().filter(s -> s.startsWith(type)).count();
    }
}
