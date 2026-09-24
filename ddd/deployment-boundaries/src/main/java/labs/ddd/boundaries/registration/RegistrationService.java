package labs.ddd.boundaries.registration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import labs.ddd.boundaries.notification.api.ConfirmationNotice;
import labs.ddd.boundaries.notification.api.NotificationPort;

/**
 * 报名用例。通知调用放在「提交」之前（同步、在事务内），失败时报名一起回滚；
 * 这正是拆分后需要重新审视的写法，对照实验见测试。
 */
public final class RegistrationService {

    private final Map<String, Session> committed = new HashMap<>();
    private final NotificationPort notifications;

    public RegistrationService(NotificationPort notifications) {
        this.notifications = notifications;
    }

    public void open(String sessionId, int capacity) {
        committed.put(sessionId, new Session(sessionId, capacity));
    }

    public synchronized Session.Outcome register(String sessionId, String attendee, String traceId) {
        Session working = committed.get(sessionId).copy();
        Session.Outcome outcome = working.register(attendee);
        if (outcome == Session.Outcome.CONFIRMED) {
            notifications.confirmationSent(new ConfirmationNotice(UUID.randomUUID().toString(), sessionId, attendee), traceId);
        }
        committed.put(sessionId, working);
        return outcome;
    }

    public synchronized int confirmed(String sessionId) {
        return committed.get(sessionId).confirmedCount();
    }
}
