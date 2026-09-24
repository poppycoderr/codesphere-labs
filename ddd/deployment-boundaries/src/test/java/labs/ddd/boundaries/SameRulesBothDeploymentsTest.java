package labs.ddd.boundaries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import labs.ddd.boundaries.deployment.HttpNotificationClient;
import labs.ddd.boundaries.deployment.InProcessNotification;
import labs.ddd.boundaries.deployment.NotificationHttpServer;
import labs.ddd.boundaries.notification.internal.NotificationService;
import labs.ddd.boundaries.registration.RegistrationService;
import labs.ddd.boundaries.registration.Session.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 同一组报名用例，分别在「同进程模块」和「通知拆成 HTTP 服务」两种部署下运行。 */
class SameRulesBothDeploymentsTest {

    NotificationService notifications = new NotificationService(false);
    NotificationHttpServer server;

    RegistrationService deploy(String mode) {
        if (mode.equals("in-process")) {
            return new RegistrationService(new InProcessNotification(notifications));
        }
        server = new NotificationHttpServer(notifications);
        return new RegistrationService(new HttpNotificationClient(server.port(), Duration.ofSeconds(2), 1));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"in-process", "http"})
    void confirmsThenWaitlists(String mode) {
        RegistrationService app = deploy(mode);
        app.open("S-1", 1);
        assertEquals(Outcome.CONFIRMED, app.register("S-1", "alice", "t1"));
        assertEquals(Outcome.WAITLISTED, app.register("S-1", "bob", "t2"));
        Facts.record("same." + mode + ".1", "确认、候补：通过");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"in-process", "http"})
    void rejectsDuplicates(String mode) {
        RegistrationService app = deploy(mode);
        app.open("S-1", 2);
        app.register("S-1", "alice", "t1");
        assertThrows(IllegalStateException.class, () -> app.register("S-1", "alice", "t2"));
        assertEquals(1, app.confirmed("S-1"));
        Facts.record("same." + mode + ".2", "重复报名被拒绝：通过");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"in-process", "http"})
    void notifiesOnlyConfirmedAttendees(String mode) {
        RegistrationService app = deploy(mode);
        app.open("S-1", 1);
        app.register("S-1", "alice", "t1");
        app.register("S-1", "bob", "t2");
        assertEquals(List.of("短信 → alice：您已报名 S-1"), notifications.delivered());
        Facts.record("same." + mode + ".3", "只通知已确认者：通过");
    }
}
