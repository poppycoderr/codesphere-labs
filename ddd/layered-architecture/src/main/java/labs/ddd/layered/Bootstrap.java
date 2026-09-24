package labs.ddd.layered;

import labs.ddd.layered.adapter.RegistrationController;
import labs.ddd.layered.application.RegisterForSession;
import labs.ddd.layered.infrastructure.InMemoryDatabase;
import labs.ddd.layered.infrastructure.LoggingEventPublisher;
import labs.ddd.layered.infrastructure.SessionConverter;
import labs.ddd.layered.infrastructure.SessionRepositoryImpl;

/** 组合根：唯一同时认识四层的地方，相当于 Spring 容器的装配。 */
public record Bootstrap(
        InMemoryDatabase db,
        LoggingEventPublisher publisher,
        RegistrationController controller) {

    public static Bootstrap wire() {
        InMemoryDatabase db = new InMemoryDatabase();
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        RegisterForSession useCase = new RegisterForSession(new SessionRepositoryImpl(db, new SessionConverter()), db, publisher);
        return new Bootstrap(db, publisher, new RegistrationController(useCase));
    }
}
