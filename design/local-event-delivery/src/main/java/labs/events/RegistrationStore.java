package labs.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 报名存储：事务内的写入先缓存，提交后才可见，回滚时丢弃；没有事务时立即生效。 */
public class RegistrationStore {
    private final List<String> committed = new CopyOnWriteArrayList<>();

    public void save(String registration) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed.add(registration);
            return;
        }
        List<String> pending = new ArrayList<>(List.of(registration));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committed.addAll(pending);
            }
        });
    }

    public List<String> committed() {
        return List.copyOf(committed);
    }
}
