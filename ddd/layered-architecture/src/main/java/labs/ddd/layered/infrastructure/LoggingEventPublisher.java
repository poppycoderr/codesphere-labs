package labs.ddd.layered.infrastructure;

import java.util.ArrayList;
import java.util.List;
import labs.ddd.layered.application.EventPublisher;
import labs.ddd.layered.domain.DomainEvent;
import labs.ddd.layered.observability.Trace;

/** 事件出口的最小实现：记录下来。可靠的跨进程投递需要 outbox，不在本实验范围内。 */
public final class LoggingEventPublisher implements EventPublisher {

    private final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
        published.add(event);
        Trace.step("infrastructure", "发布 " + event.getClass().getSimpleName());
    }

    public List<DomainEvent> published() {
        return published;
    }
}
