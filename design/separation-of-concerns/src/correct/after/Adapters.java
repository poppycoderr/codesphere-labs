package after;

import after.Ordering.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 外层适配器：HTTP、JDBC、随机数都在这里；它们依赖业务核心定义的端口 */
public final class Adapters {
    private Adapters() {}

    public static final class HttpPriceCatalog implements PriceCatalog {
        private final HttpClient http = HttpClient.newHttpClient();
        private final String baseUrl;
        public HttpPriceCatalog(String baseUrl) { this.baseUrl = baseUrl; }
        public Optional<BigDecimal> unitPrice(String sku) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/prices/" + sku)).build(), HttpResponse.BodyHandlers.ofString());
                return r.statusCode() == 404 ? Optional.empty() : Optional.of(new BigDecimal(r.body().trim()));
            } catch (Exception e) { throw new IllegalStateException("price service unavailable", e); }
        }
    }

    public static final class JdbcOrderRepository implements OrderRepository {
        private final String jdbcUrl;
        public JdbcOrderRepository(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public void save(Order o) {
            try (Connection c = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = c.prepareStatement("INSERT INTO orders (order_no, user_id, sku, quantity, amount, created_at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, o.orderNo()); ps.setString(2, o.userId()); ps.setString(3, o.sku());
                ps.setInt(4, o.quantity()); ps.setBigDecimal(5, o.amount()); ps.setTimestamp(6, Timestamp.valueOf(o.createdAt()));
                ps.executeUpdate();
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }

    public static final class RandomOrderNumbers implements OrderNumbers {
        public String next(LocalDate day) {
            return "ORD-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        }
    }

    /** HTTP 表单 → 业务请求 → JSON：格式问题只在这一层处理 */
    public static final class OrderController {
        private final OrderCreation creation;
        public OrderController(OrderCreation creation) { this.creation = creation; }
        public String create(Map<String, String> form) {
            try {
                String userId = required(form, "userId"), sku = required(form, "sku");
                int quantity = Integer.parseInt(form.getOrDefault("quantity", "1"));
                Order o = creation.create(new OrderRequest(userId, sku, quantity, "true".equals(form.get("member"))));
                return "{\"orderNo\":\"" + o.orderNo() + "\",\"amount\":" + o.amount() + "}";
            } catch (NumberFormatException e) { return "{\"error\":\"bad quantity\"}"; }
              catch (IllegalArgumentException | UnknownSkuException e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
        }
        private static String required(Map<String, String> f, String k) {
            String v = f.get(k);
            if (v == null || v.isBlank()) throw new IllegalArgumentException(k + " required");
            return v;
        }
    }
}
