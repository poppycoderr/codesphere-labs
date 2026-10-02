import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** JDBC 一侧的四道限制：多语句、只读连接、查询超时、最大行数。连接本机 3306 上的实验库。 */
public class JdbcGuard {

    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs";
    static final String BIG_JOIN = "SELECT /*+ MAX_EXECUTION_TIME(20000) */ COUNT(*) FROM events a JOIN events b ON a.kind = b.kind";

    static void out(String key, String fact) { System.out.println(key + "\t" + fact); }

    static String describe(SQLException e) {
        return e.getClass().getSimpleName() + "（" + e.getMessage().split("\n")[0] + "）";
    }

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "app_rw", "example_password")) {
            out("jdbc.driver", c.getMetaData().getDriverName() + " " + c.getMetaData().getDriverVersion().split(" ")[0]);

            try (Statement s = c.createStatement()) {
                s.execute("SELECT 1; DELETE FROM orders WHERE id = 5");
                out("jdbc.multi_statement", "执行成功");
            } catch (SQLException e) {
                out("jdbc.multi_statement", "默认连接参数下执行两条语句：" + describe(e).replaceAll("near '.*", "near …）"));
            }

            c.setReadOnly(true);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM orders WHERE id = 5");
                out("jdbc.read_only", "删除成功");
            } catch (SQLException e) {
                out("jdbc.read_only", "读写账号，setReadOnly(true) 之后执行 DELETE：" + describe(e));
            }
            c.setReadOnly(false);

            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(1);
                long t = System.nanoTime();
                try (ResultSet rs = s.executeQuery(BIG_JOIN)) {
                    rs.next();
                    out("jdbc.query_timeout", "执行完成，用时 " + (System.nanoTime() - t) / 1_000_000 + "ms");
                } catch (SQLException e) {
                    long ms = (System.nanoTime() - t) / 1_000_000;
                    out("jdbc.query_timeout", "语句自带 MAX_EXECUTION_TIME(20000) 提示，setQueryTimeout(1)：" + (ms < 3000 ? "3 秒内" : ms + "ms 后") + "抛出 " + describe(e));
                }
            }

            try (Statement s = c.createStatement()) {
                s.setMaxRows(100);
                int rows = 0;
                try (ResultSet rs = s.executeQuery("SELECT id FROM events")) { while (rs.next()) rows++; }
                out("jdbc.max_rows", "setMaxRows(100) 后查询 10 万行的表：返回 " + rows + " 行");
            }

            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM orders")) {
                rs.next();
                out("jdbc.orders_after", "实验结束后 orders 仍有 " + rs.getInt(1) + " 行");
            }
        }
    }
}
