package lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 用录制的响应做替身：替身证明的是「客户端能解析录制时的格式」，不是「对方现在还返回这个格式」 */
class RecordedResponseTest {
    static final String RECORDED = "{\"id\":\"p-1\",\"status\":\"PAID\"}";       // 半年前录下的响应
    static final String CURRENT = "{\"id\":\"p-1\",\"state\":\"PAID\"}";         // 对方现在返回的格式

    @Test void a_againstRecordedStub() { assertThat(PaymentClient.parse(RECORDED)).isEqualTo("PAID"); }

    @Test void b_againstRunningProvider() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments/p-1", ex -> {
            byte[] b = CURRENT.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.start();
        try { assertThat(new PaymentClient("http://127.0.0.1:" + server.getAddress().getPort()).status("p-1")).isEqualTo("PAID"); }
        finally { server.stop(0); }
    }
}
