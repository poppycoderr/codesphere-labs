import before.OrderCreationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 重构前唯一可行的测试：起一个假的价格服务、连真实 MySQL、最后查库断言 */
class BeforeTest {
    static HttpServer priceServer;
    static final String JDBC = "jdbc:mysql://127.0.0.1:3306/labs?user=root&password=example_password&useSSL=false&allowPublicKeyRetrieval=true";

    @BeforeAll static void setUp() throws Exception {
        priceServer = HttpServer.create(new InetSocketAddress(0), 0);
        priceServer.createContext("/prices/", ex -> { byte[] b = "80.00".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        priceServer.start();
        try (Connection c = DriverManager.getConnection(JDBC); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS orders");
            s.execute("CREATE TABLE orders (order_no VARCHAR(32) PRIMARY KEY, user_id VARCHAR(32), sku VARCHAR(32), quantity INT, amount DECIMAL(10,2), created_at DATETIME)");
        }
    }
    @AfterAll static void tearDown() { priceServer.stop(0); }

    @Test void 会员购买两件的金额() throws Exception {
        var svc = new OrderCreationService("http://127.0.0.1:" + priceServer.getAddress().getPort(), JDBC);
        String json = svc.create(Map.of("userId", "u1", "sku", "A1", "quantity", "2", "member", "true"));
        String orderNo = json.replaceAll(".*\"orderNo\":\"([^\"]+)\".*", "$1");
        try (Connection c = DriverManager.getConnection(JDBC);
             PreparedStatement ps = c.prepareStatement("SELECT amount FROM orders WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            ResultSet rs = ps.executeQuery(); assertTrue(rs.next());
            // 80 × 2 × 0.9 = 144；晚上 8 点到 10 点之间运行时是 134 —— 结果取决于测试在几点运行
            assertEquals("144.00", rs.getBigDecimal(1).toPlainString());
        }
    }
}
