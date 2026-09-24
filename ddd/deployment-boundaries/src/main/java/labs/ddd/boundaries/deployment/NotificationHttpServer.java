package labs.ddd.boundaries.deployment;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import labs.ddd.boundaries.notification.api.ConfirmationNotice;
import labs.ddd.boundaries.notification.internal.NotificationService;

/** 拆分后的通知服务：独立的 HTTP 端点，走本机回环网络。 */
public final class NotificationHttpServer implements AutoCloseable {

    private final HttpServer server;

    public NotificationHttpServer(NotificationService service) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/notices", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            service.send(ConfirmationNotice.decode(body), exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
