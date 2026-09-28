import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

/** 8080 端口：/ok 返回 200，/fail 返回 503。9090 端口没有进程监听。 */
public class Server {
    public static void main(String[] args) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(8080), 0);
        s.createContext("/ok", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        s.createContext("/fail", ex -> {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        s.start();
    }
}
