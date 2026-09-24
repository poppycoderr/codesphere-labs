package labs.ddd.boundaries.deployment;

import labs.ddd.boundaries.notification.api.ConfirmationNotice;
import labs.ddd.boundaries.notification.api.NotificationPort;
import labs.ddd.boundaries.notification.internal.NotificationService;

/** 模块化单体：同一进程内的方法调用。 */
public final class InProcessNotification implements NotificationPort {

    private final NotificationService service;

    public InProcessNotification(NotificationService service) {
        this.service = service;
    }

    @Override
    public void confirmationSent(ConfirmationNotice notice, String traceId) {
        service.send(notice, traceId);
    }
}
