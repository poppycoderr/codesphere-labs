import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/** new ServerSocket(port) 不指定 backlog 时，不 accept 的情况下能完成多少个握手 */
public class DefaultBacklog {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(9001)) {
            AtomicInteger ok = new AtomicInteger();
            Thread[] ts = new Thread[80];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = new Thread(() -> { try { Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", 9001), 2000); ok.incrementAndGet(); } catch (Exception ignored) { } });
                ts[i].start();
            }
            for (Thread t : ts) t.join();
            System.out.println("java.default\tnew ServerSocket(port) 不指定 backlog、不 accept，同时发起 80 个连接（超时 2 秒）：握手完成 " + ok.get() + " 个（java.version=" + System.getProperty("java.version") + "）");
        }
    }
}
