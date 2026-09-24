package labs.ddd.boundaries.notification.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import labs.ddd.boundaries.notification.api.ConfirmationNotice;

/** 通知上下文内部：渲染模板、选择渠道、记录投递。可以按 noticeId 去重。 */
public final class NotificationService {

    private final List<String> delivered = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final List<String> log = new ArrayList<>();
    private final boolean deduplicate;
    private volatile long latencyMillis;

    public NotificationService(boolean deduplicate) {
        this.deduplicate = deduplicate;
    }

    public void send(ConfirmationNotice notice, String traceId) {
        sleep(latencyMillis);
        record(notice, traceId);
    }

    private synchronized void record(ConfirmationNotice notice, String traceId) {
        log.add("notification traceId=" + traceId + " noticeId=" + notice.noticeId());
        if (deduplicate && !seen.add(notice.noticeId())) {
            return;
        }
        delivered.add("短信 → " + notice.attendeeId() + "：您已报名 " + notice.sessionId());
    }

    public void slowDown(long millis) {
        this.latencyMillis = millis;
    }

    public synchronized List<String> delivered() {
        return List.copyOf(delivered);
    }

    public synchronized List<String> log() {
        return List.copyOf(log);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
