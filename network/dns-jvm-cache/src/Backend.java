import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 最小 HTTP 后端：响应体是自己的名字；调用 /drain 之后，每个响应都带 Connection: close，让客户端断开重连。 */
public class Backend {
    public static void main(String[] args) throws Exception {
        String name = args[0];
        AtomicBoolean draining = new AtomicBoolean();
        HttpServer s = HttpServer.create(new InetSocketAddress(8000), 100);
        s.setExecutor(Executors.newCachedThreadPool());
        s.createContext("/drain", ex -> {
            draining.set(true);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        s.createContext("/", ex -> {
            byte[] body = name.getBytes(StandardCharsets.UTF_8);
            if (draining.get()) ex.getResponseHeaders().set("Connection", "close");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        System.out.println(name + " listening");
    }
}
