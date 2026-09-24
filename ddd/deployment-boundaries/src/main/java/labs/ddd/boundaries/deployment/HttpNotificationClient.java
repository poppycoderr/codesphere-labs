package labs.ddd.boundaries.deployment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import labs.ddd.boundaries.notification.api.ConfirmationNotice;
import labs.ddd.boundaries.notification.api.NotificationPort;

/** 拆分后的通知端口实现：超时、有限次重试、传递 traceId。 */
public final class HttpNotificationClient implements NotificationPort {

    private final HttpClient client = HttpClient.newHttpClient();
    private final URI uri;
    private final Duration timeout;
    private final int attempts;
    private final AtomicInteger calls = new AtomicInteger();

    public HttpNotificationClient(int port, Duration timeout, int attempts) {
        this.uri = URI.create("http://127.0.0.1:" + port + "/notices");
        this.timeout = timeout;
        this.attempts = attempts;
    }

    @Override
    public void confirmationSent(ConfirmationNotice notice, String traceId) {
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            calls.incrementAndGet();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("X-Trace-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(notice.encode()))
                    .build();
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding());
                return;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new NotificationUnavailable(last);
    }

    public int calls() {
        return calls.get();
    }

    /** 通知服务不可达或超时。 */
    public static final class NotificationUnavailable extends RuntimeException {
        NotificationUnavailable(IOException cause) {
            super(cause.getClass().getSimpleName(), cause);
        }
    }
}
