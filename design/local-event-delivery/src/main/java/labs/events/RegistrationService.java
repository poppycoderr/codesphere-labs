package labs.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/** 报名服务：保存报名后发布事件。register 在事务中执行，registerWithoutTransaction 不开启事务。 */
public class RegistrationService {
    private final RegistrationStore store;
    private final ApplicationEventPublisher publisher;

    public RegistrationService(RegistrationStore store, ApplicationEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Transactional
    public void register(String id, String failIn) {
        store.save(id);
        publisher.publishEvent(new Events(id, failIn));
    }

    public void registerWithoutTransaction(String id) {
        store.save(id);
        publisher.publishEvent(new Events(id, "none"));
    }
}
