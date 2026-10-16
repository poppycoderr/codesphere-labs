package lab;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 订单仓储的接口、一个内存实现（测试替身）、一个 H2 实现，以及一个有缺陷的服务 */
public final class Orders {
    private Orders() { }

    public record Order(String id, String customer, Instant createdAt, String status) { }

    public interface OrderRepository {
        Optional<Order> find(String id);
        void insert(Order o);                     // 契约：id 已存在时抛出 IllegalStateException
        List<Order> byCustomer(String customer);  // 契约：按创建时间倒序
    }

    /** 测试里常用的内存替身 */
    public static final class InMemoryOrders implements OrderRepository {
        private final Map<String, Order> rows = new LinkedHashMap<>();
        public Optional<Order> find(String id) { return Optional.ofNullable(rows.get(id)); }
        public void insert(Order o) { rows.put(o.id(), o); }
        public List<Order> byCustomer(String c) { return rows.values().stream().filter(o -> o.customer().equals(c)).toList(); }
    }

    /** 真实实现：H2 内存数据库 */
    public static final class JdbcOrders implements OrderRepository {
        private final Connection c;
        public JdbcOrders(String name) {
            try {
                c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
                c.createStatement().execute("CREATE TABLE orders (id VARCHAR PRIMARY KEY, customer VARCHAR, created_at TIMESTAMP WITH TIME ZONE, status VARCHAR)");
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }
        public Optional<Order> find(String id) { return query("SELECT * FROM orders WHERE id = ?", id).stream().findFirst(); }
        public void insert(Order o) {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO orders VALUES (?, ?, ?, ?)")) {
                p.setString(1, o.id()); p.setString(2, o.customer()); p.setObject(3, o.createdAt().atOffset(java.time.ZoneOffset.UTC)); p.setString(4, o.status());
                p.executeUpdate();
            } catch (SQLException e) { throw new IllegalStateException("订单已存在：" + o.id(), e); }
        }
        public List<Order> byCustomer(String customer) { return query("SELECT * FROM orders WHERE customer = ? ORDER BY created_at DESC", customer); }
        private List<Order> query(String sql, String arg) {
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, arg);
                List<Order> out = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) out.add(new Order(r.getString("id"), r.getString("customer"), r.getObject("created_at", java.time.OffsetDateTime.class).toInstant(), r.getString("status")));
                }
                return out;
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }

    /** 被测服务：取消订单。缺陷：用客户 id 去查订单 */
    public static final class CancelService {
        private final OrderRepository repo;
        public CancelService(OrderRepository repo) { this.repo = repo; }
        public String cancel(String orderId, String customerId) {
            Order o = repo.find(customerId).orElseThrow(() -> new IllegalArgumentException("订单不存在"));
            return o.status().equals("SHIPPED") ? "REJECTED" : "CANCELLED";
        }
    }
}
