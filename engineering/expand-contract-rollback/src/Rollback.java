import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 把报名表的 phone 列改名为 mobile，比较两种上线方式在「新旧版本并存」和「回退到旧版本」时的表现，输出为「键<TAB>事实」。
 * 版本用代码路径表示：每个版本只在自己知道的列上读写。
 * 1. 原地改名：迁移一次完成，v2 读写 mobile；
 * 2. 扩展—迁移—收缩：先加 mobile，v1.5 双写并回填，v2 读 mobile 仍双写，确认后才删 phone；
 * 3. 事件：旧消费者读取新版本事件，严格解析与宽容解析；新增字段与改名字段。
 */
public class Rollback {
    static final String URL = "jdbc:mysql://127.0.0.1:3306/labs?useSSL=false&allowPublicKeyRetrieval=true";
    static int seq = 0;

    interface Version {
        String name();

        void register(Connection c, String attendee, String phone) throws Exception;

        String phoneOf(Connection c, int id) throws Exception;
    }

    static final Version V1 = new Version() {
        public String name() {
            return "v1";
        }

        public void register(Connection c, String a, String p) throws Exception {
            exec(c, "INSERT INTO registration (attendee, phone) VALUES (?, ?)", a, p);
        }

        public String phoneOf(Connection c, int id) throws Exception {
            return one(c, "SELECT phone FROM registration WHERE id = ?", id);
        }
    };

    /** 扩展阶段的过渡版本：同时写两列，仍然读旧列。 */
    static final Version V15 = new Version() {
        public String name() {
            return "v1.5";
        }

        public void register(Connection c, String a, String p) throws Exception {
            exec(c, "INSERT INTO registration (attendee, phone, mobile) VALUES (?, ?, ?)", a, p, p);
        }

        public String phoneOf(Connection c, int id) throws Exception {
            return one(c, "SELECT phone FROM registration WHERE id = ?", id);
        }
    };

    /** 新版本（扩展—收缩路线）：读新列，收缩之前仍然双写。 */
    static final Version V2_DUAL = new Version() {
        public String name() {
            return "v2";
        }

        public void register(Connection c, String a, String p) throws Exception {
            exec(c, "INSERT INTO registration (attendee, phone, mobile) VALUES (?, ?, ?)", a, p, p);
        }

        public String phoneOf(Connection c, int id) throws Exception {
            return one(c, "SELECT mobile FROM registration WHERE id = ?", id);
        }
    };

    /** 新版本（原地改名路线，或收缩之后）：只知道新列。 */
    static final Version V2_ONLY = new Version() {
        public String name() {
            return "v2";
        }

        public void register(Connection c, String a, String p) throws Exception {
            exec(c, "INSERT INTO registration (attendee, mobile) VALUES (?, ?)", a, p);
        }

        public String phoneOf(Connection c, int id) throws Exception {
            return one(c, "SELECT mobile FROM registration WHERE id = ?", id);
        }
    };

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "root", "example_password")) {
            inPlaceRename(c);
            expandContract(c);
            matrix(c);
        }
        events();
    }

    // ---------- 1. 原地改名 ----------

    static void inPlaceRename(Connection c) throws Exception {
        reset(c, false);
        traffic(c, "rename.before", List.of(V1));
        ddl(c, "ALTER TABLE registration RENAME COLUMN phone TO mobile");
        traffic(c, "rename.rolling", List.of(V1, V2_ONLY));             // 滚动发布：新旧实例同时在服务
        traffic(c, "rename.after", List.of(V2_ONLY));
        traffic(c, "rename.rollback", List.of(V1));                     // v2 有问题，镜像回退到 v1
        out("rename.rollback_needs", "回退还需要反向迁移 RENAME COLUMN mobile TO phone；在它执行之前 v1 的每个请求都失败");
    }

    // ---------- 2. 扩展—迁移—收缩 ----------

    static void expandContract(Connection c) throws Exception {
        reset(c, false);
        traffic(c, "ec.before", List.of(V1));
        ddl(c, "ALTER TABLE registration ADD COLUMN mobile VARCHAR(32) NULL");     // 扩展：只加列，旧版本不受影响
        traffic(c, "ec.expand", List.of(V1));
        traffic(c, "ec.dual_write_rolling", List.of(V1, V15));
        int filled = exec(c, "UPDATE registration SET mobile = phone WHERE mobile IS NULL");  // 回填历史数据
        out("ec.backfill", "回填 %d 行".formatted(filled));
        traffic(c, "ec.read_new_rolling", List.of(V15, V2_DUAL));
        traffic(c, "ec.rollback_to_v1_5", List.of(V15));                  // v2 有问题，回退
        traffic(c, "ec.rollback_to_v1", List.of(V1));                     // 继续回退到最早的版本也行
        consistency(c, "ec.consistency_after_rollback");
        traffic(c, "ec.redeploy_v2", List.of(V2_DUAL));
        ddl(c, "UPDATE registration SET mobile = phone WHERE mobile IS NULL");      // v1 回退期间写入的行
        consistency(c, "ec.consistency_before_contract");
        ddl(c, "ALTER TABLE registration DROP COLUMN phone");                        // 收缩：不可逆点
        traffic(c, "ec.after_contract", List.of(V2_ONLY));
        traffic(c, "ec.rollback_after_contract", List.of(V1));
    }

    /** 每个版本分别在三种表结构上各跑 20 次。 */
    static void matrix(Connection c) throws Exception {
        String[][] schemas = {
                {"phone_only", "phone VARCHAR(32) NULL"},
                {"both", "phone VARCHAR(32) NULL, mobile VARCHAR(32) NULL"},
                {"mobile_only", "mobile VARCHAR(32) NULL"},
        };
        Version[] versions = {V1, V15, V2_DUAL, V2_ONLY};
        String[] names = {"v1", "v1.5", "v2_dual", "v2_only"};
        for (String[] sc : schemas) {
            for (int i = 0; i < versions.length; i++) {
                ddl(c, "DROP TABLE IF EXISTS registration");
                ddl(c, "CREATE TABLE registration (id INT AUTO_INCREMENT PRIMARY KEY, attendee VARCHAR(64) NOT NULL, " + sc[1] + ")");
                traffic(c, "matrix." + names[i] + "." + sc[0], List.of(versions[i]));
            }
        }
    }

    static void consistency(Connection c, String key) throws Exception {
        String mismatch = one(c, "SELECT COUNT(*) FROM registration WHERE NOT (phone <=> mobile)");
        String nulls = one(c, "SELECT COUNT(*) FROM registration WHERE mobile IS NULL");
        out(key, "phone 与 mobile 不一致的行 %s，mobile 为空的行 %s".formatted(mismatch, nulls));
    }

    /** 每个在线版本各处理 20 次「报名 + 读回手机号」。 */
    static void traffic(Connection c, String key, List<Version> online) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Version v : online) {
            int ok = 0;
            int fail = 0;
            String firstError = null;
            for (int i = 0; i < 20; i++) {
                String phone = "138" + String.format("%08d", ++seq);
                try {
                    v.register(c, "attendee-" + seq, phone);
                    int id = Integer.parseInt(one(c, "SELECT LAST_INSERT_ID()"));
                    if (phone.equals(v.phoneOf(c, id))) {
                        ok++;
                    } else {
                        fail++;
                    }
                } catch (Exception e) {
                    fail++;
                    firstError = firstError == null ? e.getMessage() : firstError;
                }
            }
            sb.append("%s 成功 %d、失败 %d%s；".formatted(v.name(), ok, fail, firstError == null ? "" : "（" + firstError + "）"));
        }
        out(key, sb.toString().replaceAll("；$", ""));
    }

    // ---------- 3. 事件 ----------

    /** 旧消费者只认识这些字段。 */
    public record RegisteredV1(long id, String attendee, String phone) {
    }

    static void events() throws Exception {
        String added = "{\"id\":1,\"attendee\":\"a\",\"phone\":\"13800000001\",\"channel\":\"poster\"}";   // v2 新增字段
        String renamed = "{\"id\":2,\"attendee\":\"b\",\"mobile\":\"13800000002\"}";                      // v2 改名字段
        ObjectMapper strict = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        ObjectMapper tolerant = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        for (var e : List.of(List.of("added", added), List.of("renamed", renamed))) {
            out("event." + e.get(0) + ".strict", parse(strict, e.get(1)));
            out("event." + e.get(0) + ".tolerant", parse(tolerant, e.get(1)));
        }
    }

    static String parse(ObjectMapper m, String json) {
        try {
            return "解析成功：" + m.readValue(json, RegisteredV1.class);
        } catch (Exception ex) {
            return "解析失败：" + ex.getClass().getSimpleName() + "（" + ex.getMessage().split("\n")[0] + "）";
        }
    }

    // ---------- 工具 ----------

    static void reset(Connection c, boolean withMobile) throws Exception {
        ddl(c, "DROP TABLE IF EXISTS registration");
        ddl(c, "CREATE TABLE registration (id INT AUTO_INCREMENT PRIMARY KEY, attendee VARCHAR(64) NOT NULL, phone VARCHAR(32) NULL)");
    }

    static void ddl(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static int exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            return p.executeUpdate();
        }
    }

    static String one(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                p.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
