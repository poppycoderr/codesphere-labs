import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 一个金额从哪里来：订单行引用当前价格、订单行保存成交价快照、价格按有效期分版本、报价的有效期、
 * 发票与来源订单之间的凭证链。所有数据固定，输出确定。
 */
public class ProvenanceLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static Connection pg() throws SQLException { return DriverManager.getConnection("jdbc:postgresql://postgres:5432/labs", "postgres", "example_password"); }
    static void exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    static String one(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.next() ? r.getString(1) : null; } }
    static String tryExec(Connection c, String sql) {
        try { exec(c, sql); return "成功"; } catch (SQLException e) { return "被拒绝（" + e.getMessage().split("\n")[0].replace("ERROR: ", "") + "）"; }
    }

    public static void main(String[] args) throws Exception {
        try (Connection p = pg()) {
            out("env", "java.version=" + System.getProperty("java.version") + " postgres=" + p.getMetaData().getDatabaseProductVersion().split(" ")[0]);
            exec(p, "CREATE EXTENSION IF NOT EXISTS btree_gist");
            exec(p, "DROP TABLE IF EXISTS product, order_line_ref, order_line_snap, price_version, order_line_ver, quote, invoice, orders CASCADE");

            // 一、订单行只引用商品，金额按当前价格计算
            exec(p, "CREATE TABLE product (id TEXT PRIMARY KEY, price NUMERIC(10,2) NOT NULL)");
            exec(p, "INSERT INTO product VALUES ('A', 100.00), ('B', 30.00)");
            exec(p, "CREATE TABLE order_line_ref (order_id TEXT, product_id TEXT REFERENCES product, qty INT)");
            exec(p, "INSERT INTO order_line_ref VALUES ('o-1', 'A', 2), ('o-1', 'B', 1)");
            String totalRef = "SELECT SUM(l.qty * p.price) FROM order_line_ref l JOIN product p ON p.id = l.product_id WHERE l.order_id = 'o-1'";
            String before = one(p, totalRef);
            exec(p, "UPDATE product SET price = 120.00 WHERE id = 'A'");
            out("reference.reprice", "10 月 1 日下单 o-1（A×2、B×1），当时合计 " + before + "；10 月 20 日 A 调价到 120 后再查这张历史订单：" + one(p, totalRef));

            // 二、订单行保存成交时的单价（快照）
            exec(p, "CREATE TABLE order_line_snap (order_id TEXT, product_id TEXT REFERENCES product, qty INT, unit_price NUMERIC(10,2) NOT NULL)");
            exec(p, "UPDATE product SET price = 100.00 WHERE id = 'A'");
            exec(p, "INSERT INTO order_line_snap SELECT 'o-2', id, CASE id WHEN 'A' THEN 2 ELSE 1 END, price FROM product");
            exec(p, "UPDATE product SET price = 120.00 WHERE id = 'A'");
            out("snapshot.reprice", "下单时把单价抄进订单行，同样调价后 o-2 的合计：" + one(p, "SELECT SUM(qty * unit_price) FROM order_line_snap WHERE order_id = 'o-2'")
                    + "；但快照只有一个数字，问「这个 100 是标价、会员价还是促销价」答不上来");

            // 三、价格按有效期分版本，订单行引用版本
            exec(p, "CREATE TABLE price_version (id SERIAL PRIMARY KEY, product_id TEXT REFERENCES product, price NUMERIC(10,2), kind TEXT, valid DATERANGE,"
                    + " EXCLUDE USING gist (product_id WITH =, kind WITH =, valid WITH &&))");
            exec(p, "INSERT INTO price_version (product_id, price, kind, valid) VALUES ('A', 100.00, 'LIST', '[2026-01-01, 2026-10-20)'), ('A', 120.00, 'LIST', '[2026-10-20,)'),"
                    + " ('A', 90.00, 'PROMO', '[2026-10-01, 2026-10-08)'), ('B', 30.00, 'LIST', '[2026-01-01,)')");
            out("version.overlap", "给 A 再加一个与现有标价重叠的版本：" + tryExec(p, "INSERT INTO price_version (product_id, price, kind, valid) VALUES ('A', 110.00, 'LIST', '[2026-10-15, 2026-11-01)')"));
            exec(p, "CREATE TABLE order_line_ver (order_id TEXT, product_id TEXT, qty INT, unit_price NUMERIC(10,2) NOT NULL, price_version_id INT REFERENCES price_version)");
            exec(p, "INSERT INTO order_line_ver SELECT 'o-3', v.product_id, 2, v.price, v.id FROM price_version v WHERE v.product_id = 'A' AND v.kind = 'PROMO' AND v.valid @> DATE '2026-10-05'");
            out("version.trace", "10 月 5 日下单 o-3，单价与来源：" + one(p, "SELECT l.unit_price || ' 来自 ' || v.kind || ' 版本 #' || v.id || ' ' || v.valid FROM order_line_ver l JOIN price_version v ON v.id = l.price_version_id WHERE l.order_id = 'o-3'"));
            out("version.as_of", "按日期回答「A 当天的标价」：10 月 5 日 " + one(p, "SELECT price FROM price_version WHERE product_id = 'A' AND kind = 'LIST' AND valid @> DATE '2026-10-05'")
                    + "，10 月 25 日 " + one(p, "SELECT price FROM price_version WHERE product_id = 'A' AND kind = 'LIST' AND valid @> DATE '2026-10-25'"));
            out("version.delete_used", "删除已被订单引用的促销价版本：" + tryExec(p, "DELETE FROM price_version WHERE kind = 'PROMO'"));

            // 四、报价有效期：接受时按报价的有效期判断，而不是按当前价格重算
            exec(p, "CREATE TABLE quote (id TEXT PRIMARY KEY, amount NUMERIC(10,2), valid_until DATE)");
            exec(p, "INSERT INTO quote VALUES ('q-1', 200.00, '2026-10-10')");
            String accept = "SELECT CASE WHEN DATE '%s' <= valid_until THEN '按报价 ' || amount || ' 成交' ELSE '报价已过期，需重新报价' END FROM quote WHERE id = 'q-1'";
            out("quote.validity", "q-1 报价 200、有效期至 10 月 10 日：10 月 9 日接受 → " + one(p, String.format(accept, "2026-10-09")) + "；10 月 12 日接受 → " + one(p, String.format(accept, "2026-10-12")));

            // 五、凭证链：发票记录它来自哪张订单的哪个版本，下游能发现上游被改
            exec(p, "CREATE TABLE orders (id TEXT PRIMARY KEY, version INT NOT NULL, total NUMERIC(10,2))");
            exec(p, "INSERT INTO orders VALUES ('o-4', 1, 230.00)");
            exec(p, "CREATE TABLE invoice (id TEXT PRIMARY KEY, order_id TEXT REFERENCES orders, order_version INT, amount NUMERIC(10,2))");
            exec(p, "INSERT INTO invoice VALUES ('inv-1', 'o-4', 1, 230.00)");
            exec(p, "UPDATE orders SET total = 200.00, version = version + 1 WHERE id = 'o-4'");
            out("chain.drift", "开票后订单被改成 200（版本 2）：发票仍是 230；按「发票记录的订单版本 ≠ 订单当前版本」能查出不一致的发票 "
                    + one(p, "SELECT COUNT(*) FROM invoice i JOIN orders o ON o.id = i.order_id WHERE i.order_version <> o.version") + " 张；只存 order_id 时，这张发票看起来与订单「对不上」，却说不清是哪一方变了");
            exec(p, "CREATE FUNCTION forbid_invoiced() RETURNS trigger AS $$ BEGIN IF EXISTS (SELECT 1 FROM invoice WHERE order_id = OLD.id) THEN RAISE EXCEPTION '订单 % 已开票，不能修改金额，请开红字发票或更正单', OLD.id; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
            exec(p, "CREATE TRIGGER orders_invoiced BEFORE UPDATE OF total ON orders FOR EACH ROW EXECUTE FUNCTION forbid_invoiced()");
            out("chain.freeze", "加上「已开票的订单金额不可改」的触发器后再改：" + tryExec(p, "UPDATE orders SET total = 180.00, version = version + 1 WHERE id = 'o-4'"));
        }
    }
}
