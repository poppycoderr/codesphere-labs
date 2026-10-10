import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数化查询管得到与管不到的地方：值的位置可以绑定参数，排序列、方向、表名这些「结构」的位置不行；
 * 绑定了参数的 LIKE 与 IN 不会被注入，但结果可能不是想要的。对一个本地的 MySQL 容器按固定顺序执行，输出确定。
 */
public class InjectionLab {
    static final String BASE = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static List<String> column(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { List<String> r = new ArrayList<>(); while (rs.next()) r.add(rs.getString(1)); return r; }
        }
    }
    static List<String> raw(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { List<String> r = new ArrayList<>(); while (rs.next()) r.add(rs.getString(1)); return r; }
    }
    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName(); } }

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(BASE, "root", "example_password")) {
            out("env", "java.version=" + System.getProperty("java.version") + " mysql=" + c.getMetaData().getDatabaseProductVersion() + " driver=" + c.getMetaData().getDriverVersion().split(" ")[0]);
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS products, accounts");
                st.execute("CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR(64), price INT, owner VARCHAR(32))");
                st.execute("INSERT INTO products VALUES (1,'usb cable',19,'alice'),(2,'usb hub',89,'alice'),(3,'100% cotton shirt',59,'bob'),(4,'a_b tester',9,'bob'),(5,'axb adapter',39,'carol')");
                st.execute("CREATE TABLE accounts (username VARCHAR(32) PRIMARY KEY, api_token VARCHAR(64), role VARCHAR(16))");
                st.execute("INSERT INTO accounts VALUES ('admin','tok_k7','admin'),('alice','tok_a1','user')");
            }

            // 一、值的位置：拼接与绑定参数
            String input = "alice' OR '1'='1";
            out("value.concat", "owner 传入 alice' OR '1'='1，拼进 SQL：返回 " + raw(c, "SELECT name FROM products WHERE owner = '" + input + "' ORDER BY id").size() + " 行（alice 只有 2 行）");
            out("value.bound", "同样的输入，绑定参数：返回 " + column(c, "SELECT name FROM products WHERE owner = ? ORDER BY id", input).size() + " 行");
            String numeric = "1 OR 1=1";
            out("value.numeric", "id 传入 1 OR 1=1，数值不加引号直接拼接：返回 " + raw(c, "SELECT name FROM products WHERE id = " + numeric).size() + " 行（输入里没有任何引号）");
            out("value.union", "id 传入 0 UNION SELECT api_token FROM accounts，拼接后读到别的表：" + raw(c, "SELECT name FROM products WHERE id = 0 UNION SELECT api_token FROM accounts ORDER BY 1"));

            // 二、排序列绑定不了
            out("order.bound", "ORDER BY ? 绑定 \"price\"：返回顺序 " + column(c, "SELECT id FROM products ORDER BY ?", "price") + "（按价格应为 [4, 1, 5, 3, 2]）");
            out("order.bound_desc", "ORDER BY price ? 绑定 \"DESC\"：" + attempt(() -> column(c, "SELECT id FROM products ORDER BY price ?", "DESC")));
            String sort = "price";
            out("order.concat", "把排序列拼进 SQL，传入 price：" + raw(c, "SELECT id FROM products ORDER BY " + sort));
            // 布尔盲注：排序结果随「子查询的条件是否成立」变化，一次读出一位信息
            StringBuilder leaked = new StringBuilder();
            String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789_";
            for (int pos = 1; pos <= 6; pos++) {
                for (char ch : alphabet.toCharArray()) {
                    String payload = "(CASE WHEN (SELECT SUBSTRING(api_token," + pos + ",1) FROM accounts WHERE username='admin')='" + ch + "' THEN id ELSE -id END)";
                    if (raw(c, "SELECT id FROM products ORDER BY " + payload).get(0).equals("1")) { leaked.append(ch); break; }
                }
            }
            out("order.blind", "排序参数里放一个 CASE 子查询，只看返回的第一行是谁，逐位猜 admin 的 api_token：读出 " + leaked);
            Map<String, String> allowed = Map.of("price", "price", "name", "name", "newest", "id DESC");
            out("order.allowlist", "排序参数先查白名单再拼接：传入 newest → " + raw(c, "SELECT id FROM products ORDER BY " + allowed.get("newest"))
                    + "；传入那个 CASE 子查询 → " + (allowed.get("(CASE WHEN 1=1 THEN id ELSE -id END)") == null ? "不在白名单里，拒绝" : "放行"));

            // 三、LIKE：绑定了参数，通配符仍然是通配符
            out("like.percent", "搜索框输入 %，LIKE CONCAT('%', ?, '%')：返回 " + column(c, "SELECT name FROM products WHERE name LIKE CONCAT('%', ?, '%')", "%").size() + " 行（名字里真有百分号的只有 1 个）");
            out("like.underscore", "输入 a_b：返回 " + column(c, "SELECT name FROM products WHERE name LIKE CONCAT('%', ?, '%') ORDER BY id", "a_b"));
            String escaped = "a_b".replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            out("like.escaped", "把输入里的 \\、%、_ 转义后再绑定：" + column(c, "SELECT name FROM products WHERE name LIKE CONCAT('%', ?, '%') ORDER BY id", escaped));

            // 四、IN：一个占位符只能放一个值
            out("in.one_placeholder", "IN (?) 绑定字符串 \"1,2,3\"：返回 id " + column(c, "SELECT id FROM products WHERE id IN (?) ORDER BY id", "1,2,3") + "（想要的是 [1, 2, 3]）");
            out("in.placeholders", "IN (?, ?, ?) 逐个绑定：返回 id " + column(c, "SELECT id FROM products WHERE id IN (?, ?, ?) ORDER BY id", 1, 2, 3));

            // 五、一次执行多条语句
            String stacked = "1; UPDATE accounts SET role = 'admin' WHERE username = 'alice'";
            out("stacked.default", "id 传入 1; UPDATE accounts SET role='admin' …，默认连接参数下拼接执行：" + attempt(() -> raw(c, "SELECT name FROM products WHERE id = " + stacked)) + "；alice 的角色 " + raw(c, "SELECT role FROM accounts WHERE username='alice'"));
            try (Connection multi = DriverManager.getConnection(BASE + "&allowMultiQueries=true", "root", "example_password"); Statement st = multi.createStatement()) {
                st.execute("SELECT name FROM products WHERE id = " + stacked);
                out("stacked.multi", "连接串加了 allowMultiQueries=true 之后同样的输入：alice 的角色 " + raw(c, "SELECT role FROM accounts WHERE username='alice'"));
            }

            // 六、存进去的时候是安全的，取出来再拼就不是了
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO products VALUES (6, 'webcam', 129, ?)")) { ps.setString(1, "x' OR '1'='1"); ps.executeUpdate(); }
            String storedOwner = raw(c, "SELECT owner FROM products WHERE id = 6").get(0);
            out("second_order", "用绑定参数存入 owner = x' OR '1'='1；另一处代码把它从库里读出来拼进 SQL：返回 " + raw(c, "SELECT name FROM products WHERE owner = '" + storedOwner + "'").size() + " 行（这个 owner 名下只有 1 行）");
        }
    }
}
