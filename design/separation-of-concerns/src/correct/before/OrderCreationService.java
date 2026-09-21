package before;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 重构前：校验、调价格服务、计价规则、时间、随机编号、持久化、响应格式都在一个方法里 */
public class OrderCreationService {
    private final String priceServiceUrl;
    private final String jdbcUrl;

    public OrderCreationService(String priceServiceUrl, String jdbcUrl) {
        this.priceServiceUrl = priceServiceUrl;
        this.jdbcUrl = jdbcUrl;
    }

    public String create(Map<String, String> form) throws Exception {
        // 1. 校验（HTTP 表单格式与业务规则混在一起）
        String userId = form.get("userId");
        String sku = form.get("sku");
        if (userId == null || userId.isBlank()) return "{\"error\":\"userId required\"}";
        if (sku == null || sku.isBlank()) return "{\"error\":\"sku required\"}";
        int quantity;
        try { quantity = Integer.parseInt(form.getOrDefault("quantity", "1")); }
        catch (NumberFormatException e) { return "{\"error\":\"bad quantity\"}"; }
        if (quantity < 1 || quantity > 99) return "{\"error\":\"quantity out of range\"}";
        boolean member = "true".equals(form.get("member"));

        // 2. 调价格服务（网络 I/O）
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(priceServiceUrl + "/prices/" + sku)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return "{\"error\":\"unknown sku\"}";
        BigDecimal unitPrice = new BigDecimal(resp.body().trim());

        // 3. 计价规则（会员 9 折；20:00—22:00 满 100 减 10）
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (member) amount = amount.multiply(new BigDecimal("0.9"));
        LocalTime now = LocalTime.now();                                   // 直接读系统时间
        if (!now.isBefore(LocalTime.of(20, 0)) && now.isBefore(LocalTime.of(22, 0))
                && amount.compareTo(new BigDecimal("100")) >= 0) {
            amount = amount.subtract(BigDecimal.TEN);
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);

        // 4. 订单号（时间 + 随机数）
        String orderNo = "ORD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + String.format("%06d", new Random().nextInt(1_000_000));

        // 5. 持久化
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO orders (order_no, user_id, sku, quantity, amount, created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, orderNo); ps.setString(2, userId); ps.setString(3, sku);
            ps.setInt(4, quantity); ps.setBigDecimal(5, amount);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }

        // 6. 响应格式
        return "{\"orderNo\":\"" + orderNo + "\",\"amount\":" + amount + "}";
    }
}
