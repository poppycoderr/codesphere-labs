package labs.ddd.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import labs.ddd.boundaries.deployment.HttpNotificationClient;
import labs.ddd.boundaries.deployment.InProcessNotification;
import labs.ddd.boundaries.deployment.NotificationHttpServer;
import labs.ddd.boundaries.notification.api.ConfirmationNotice;
import labs.ddd.boundaries.notification.api.NotificationPort;
import labs.ddd.boundaries.notification.internal.NotificationService;
import labs.ddd.boundaries.registration.RegistrationService;
import org.junit.jupiter.api.Test;

/** 只在拆分后才出现的失败方式：对方不在、超时后对方其实做完了、网络调用的额外延迟、跨进程的 traceId。 */
class SplitFailureModesTest {

    @Test
    void notificationServiceDown() {
        NotificationService notifications = new NotificationService(false);
        NotificationHttpServer server = new NotificationHttpServer(notifications);
        int port = server.port();
        server.close();
        RegistrationService app = new RegistrationService(new HttpNotificationClient(port, Duration.ofMillis(300), 1));
        app.open("S-1", 100);
        int failed = 0;
        String error = "";
        for (int i = 0; i < 20; i++) {
            try {
                app.register("S-1", "u" + i, "t" + i);
            } catch (HttpNotificationClient.NotificationUnavailable e) {
                failed++;
                error = e.getMessage();
            }
        }
        assertEquals(20, failed);
        assertEquals(0, app.confirmed("S-1"));
        Facts.record("split.down", "通知服务停止：20 次报名失败 " + failed + " 次（" + error + "），已确认 " + app.confirmed("S-1"));
    }

    @Test
    void timeoutWhileTheServerKeepsWorking() throws Exception {
        for (boolean dedup : List.of(false, true)) {
            NotificationService notifications = new NotificationService(dedup);
            notifications.slowDown(800);
            try (NotificationHttpServer server = new NotificationHttpServer(notifications)) {
                HttpNotificationClient client = new HttpNotificationClient(server.port(), Duration.ofMillis(300), 3);
                RegistrationService app = new RegistrationService(client);
                app.open("S-1", 100);
                int failed = 0;
                long t0 = System.nanoTime();
                for (int i = 0; i < 5; i++) {
                    try {
                        app.register("S-1", "u" + i, "t" + i);
                    } catch (HttpNotificationClient.NotificationUnavailable e) {
                        failed++;
                    }
                }
                long perRegistration = (System.nanoTime() - t0) / 1_000_000 / 5;
                Thread.sleep(2_500);
                Facts.record("split.timeout." + (dedup ? "dedup" : "plain"),
                        "通知耗时 800ms、客户端超时 300ms、最多 3 次：5 次报名失败 " + failed + " 次，已确认 " + app.confirmed("S-1")
                                + "，HTTP 调用 " + client.calls() + " 次，通知服务实际发出短信 " + notifications.delivered().size()
                                + " 条，每次报名约 " + (perRegistration / 100 * 100) + "ms");
                assertEquals(5, failed);
                assertEquals(dedup ? 5 : 15, notifications.delivered().size());
            }
        }
    }

    @Test
    void networkCallLatency() {
        NotificationService notifications = new NotificationService(false);
        try (NotificationHttpServer server = new NotificationHttpServer(notifications)) {
            NotificationPort local = new InProcessNotification(notifications);
            NotificationPort remote = new HttpNotificationClient(server.port(), Duration.ofSeconds(2), 1);
            long localMedian = median(local, 500);
            long remoteMedian = median(remote, 500);
            assertTrue(remoteMedian > localMedian);
            Facts.record("split.latency", "每次通知调用的中位耗时：同进程 " + localMedian + "µs，本机 HTTP " + remoteMedian + "µs（各 500 次，前 100 次预热不计）");
        }
    }

    @Test
    void traceIdCrossesTheBoundary() {
        NotificationService notifications = new NotificationService(false);
        try (NotificationHttpServer server = new NotificationHttpServer(notifications)) {
            RegistrationService app = new RegistrationService(new HttpNotificationClient(server.port(), Duration.ofSeconds(2), 1));
            app.open("S-1", 1);
            app.register("S-1", "alice", "trace-7f3a");
            assertTrue(notifications.log().getFirst().startsWith("notification traceId=trace-7f3a "));
            Facts.record("split.trace", "报名请求 traceId=trace-7f3a，通知服务日志：" + notifications.log().getFirst().replaceAll("noticeId=.*", "noticeId=<uuid>"));
        }
    }

    private static long median(NotificationPort port, int n) {
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            port.confirmationSent(new ConfirmationNotice(UUID.randomUUID().toString(), "S-1", "u" + i), "t");
            if (i >= 100) {
                samples.add((System.nanoTime() - t0) / 1_000);
            }
        }
        Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }
}
