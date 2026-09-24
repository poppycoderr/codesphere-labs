package labs.ddd.layered.application;

import labs.ddd.layered.domain.DomainEvent;

/** 应用层声明的事件出口。 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
