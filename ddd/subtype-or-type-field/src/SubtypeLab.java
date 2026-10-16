import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * 工时登记目标的几种建模方式：类型字段加带 default 的分支、sealed 接口加穷尽的 switch、违反可替换性的子类、
 * 默认值与覆盖值的两种继承语义，以及子类型分表时的跨表身份与引用完整性（PostgreSQL）。所有输入固定，输出确定。
 */
public class SubtypeLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    // ---------- 一、类型字段 + 带 default 的分支 ----------
    static BigDecimal hourlyRateByType(String type) {
        switch (type) {
            case "CLIENT": return new BigDecimal("300");
            default: return new BigDecimal("0");          // 写这段代码时只有 CLIENT 和 INTERNAL
        }
    }
    static boolean billableByType(String type) {
        switch (type) {
            case "INTERNAL": return false;
            default: return true;                          // 另一个人写的分支，default 的含义正好相反
        }
    }

    // ---------- 三、违反可替换性 ----------
    static class Project {
        final String name; int charged;
        Project(String name) { this.name = name; }
        void charge(int hours) { charged += hours; }
    }
    static class LeaveActivity extends Project {          // 为了复用「可登记工时」而继承
        LeaveActivity(String name) { super(name); }
        @Override void charge(int hours) { throw new UnsupportedOperationException("休假不能计费"); }
    }

    // ---------- 四、默认值与覆盖值 ----------
    static final class TenantPolicy { BigDecimal maxDaily = new BigDecimal("8"); boolean allowOverride = true; }
    record ProjectCopied(String name, BigDecimal maxDaily) { }                  // 创建时复制租户默认值
    record ProjectInherited(String name, Optional<BigDecimal> override) {      // 只存覆盖值，其余动态继承
        BigDecimal effective(TenantPolicy t) { return t.allowOverride ? override.orElse(t.maxDaily) : t.maxDaily; }
    }

    static Connection pg() throws SQLException { return DriverManager.getConnection("jdbc:postgresql://postgres:5432/labs", "postgres", "example_password"); }
    static void exec(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    static String tryExec(Connection c, String sql) {
        try { exec(c, sql); return "成功"; } catch (SQLException e) { return "被拒绝（" + e.getMessage().split("\n")[0].replace("ERROR: ", "") + "）"; }
    }
    static String q(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            int n = r.getMetaData().getColumnCount();
            while (r.next()) { StringBuilder b = new StringBuilder("("); for (int i = 1; i <= n; i++) b.append(i > 1 ? ", " : "").append(r.getString(i)); rows.add(b.append(")").toString()); }
            return String.join(" ", rows);
        }
    }

    public static void main(String[] args) throws Exception {
        try (Connection p = pg()) {
            out("env", "java.version=" + System.getProperty("java.version") + " postgres=" + p.getMetaData().getDatabaseProductVersion().split(" ")[0]);
        }

        // 一、新增一个类型值：两段带 default 的分支各自「接住」了它
        out("typefield.new_value", "新增类型 LEAVE（休假）后没有改任何分支：时薪 " + hourlyRateByType("LEAVE") + "，可计费 " + billableByType("LEAVE") + "：一处按内部项目处理，另一处按客户项目处理");

        // 二、sealed 接口 + 没有 default 的 switch 表达式：新增子类型时编译失败，列出每一处要补的分支
        Path dir = Files.createTempDirectory("sealed");
        String before = """
                sealed interface WorkTarget permits ClientProject, InternalProject { }
                record ClientProject(String name) implements WorkTarget { }
                record InternalProject(String name) implements WorkTarget { }
                class Billing {
                    static int hourlyRate(WorkTarget t) {
                        return switch (t) { case ClientProject c -> 300; case InternalProject i -> 0; };
                    }
                }
                """;
        String after = before.replace("permits ClientProject, InternalProject", "permits ClientProject, InternalProject, LeaveActivity")
                + "record LeaveActivity(String reason) implements WorkTarget { }\n";
        out("sealed.before", compile(dir, before));
        out("sealed.after", "加入 LeaveActivity、不改 Billing：" + compile(dir, after));

        // 三、为了复用而继承：通用代码按父类型调用，子类拒绝了父类承诺的操作
        List<Project> targets = List.of(new Project("客户项目 A"), new LeaveActivity("年假"));
        String r;
        try { for (Project t : targets) t.charge(2); r = "全部成功"; } catch (UnsupportedOperationException e) { r = "第 2 个目标抛出 UnsupportedOperationException（" + e.getMessage() + "），第 1 个已经计费 " + targets.get(0).charged + " 小时"; }
        out("lsp.charge_all", "对所有登记目标统一 charge(2)：" + r);

        // 四、默认值与覆盖值：复制与动态继承在租户修改默认值、关闭覆盖时给出不同结果
        TenantPolicy tenant = new TenantPolicy();
        ProjectCopied copiedA = new ProjectCopied("A", tenant.maxDaily);
        ProjectInherited inheritedA = new ProjectInherited("A", Optional.empty());
        ProjectInherited inheritedB = new ProjectInherited("B", Optional.of(new BigDecimal("10")));
        tenant.maxDaily = new BigDecimal("7.5");
        out("default.changed", "租户把每日上限从 8 改为 7.5：创建时复制的项目 A 仍是 " + copiedA.maxDaily() + "；动态继承的项目 A 变成 " + inheritedA.effective(tenant) + "；覆盖为 10 的项目 B 仍是 " + inheritedB.effective(tenant));
        tenant.allowOverride = false;
        out("override.disabled", "租户随后关闭「允许项目覆盖」：项目 B 存着的覆盖值仍是 10，生效值变成 " + inheritedB.effective(tenant) + "；B 之前按 10 小时登记的工时是否仍然有效，要看规则是否带版本或生效日期");

        // 五、子类型分表时的身份与引用
        try (Connection p = pg()) {
            exec(p, "DROP TABLE IF EXISTS time_entry, client_project, leave_activity, t2_entry, t2_client, t2_leave, work_target CASCADE");
            exec(p, "CREATE TABLE client_project (id SERIAL PRIMARY KEY, name TEXT)");
            exec(p, "CREATE TABLE leave_activity (id SERIAL PRIMARY KEY, reason TEXT)");
            exec(p, "INSERT INTO client_project (name) VALUES ('客户项目 A')");
            exec(p, "INSERT INTO leave_activity (reason) VALUES ('年假')");
            out("per_table.ids", "每个具体类型一张表、各自自增：client_project " + q(p, "SELECT id, name FROM client_project") + "，leave_activity " + q(p, "SELECT id, reason FROM leave_activity") + "：两个目标的 id 都是 1");
            exec(p, "CREATE TABLE time_entry (id SERIAL PRIMARY KEY, target_type TEXT, target_id INT, hours INT)");
            String dangling = tryExec(p, "INSERT INTO time_entry (target_type, target_id, hours) VALUES ('LEAVE', 99, 8)");
            exec(p, "INSERT INTO time_entry (target_type, target_id, hours) VALUES ('CLIENT', 1, 2)");
            exec(p, "DELETE FROM client_project WHERE id = 1");
            long orphans = Long.parseLong(q(p, "SELECT COUNT(*) FROM time_entry e WHERE (e.target_type = 'CLIENT' AND NOT EXISTS (SELECT 1 FROM client_project c WHERE c.id = e.target_id)) OR (e.target_type = 'LEAVE' AND NOT EXISTS (SELECT 1 FROM leave_activity l WHERE l.id = e.target_id))").replaceAll("[()]", ""));
            out("per_table.reference", "工时表用 (target_type, target_id) 引用，无法声明外键：引用不存在的 LEAVE 99 " + dangling + "；删除客户项目 1 也成功；现在有 " + orphans + " 条工时指向不存在的目标");

            exec(p, "CREATE TABLE work_target (id SERIAL PRIMARY KEY, kind TEXT NOT NULL CHECK (kind IN ('CLIENT', 'LEAVE')), UNIQUE (id, kind))");
            exec(p, "CREATE TABLE t2_client (id INT PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'CLIENT' CHECK (kind = 'CLIENT'), name TEXT, FOREIGN KEY (id, kind) REFERENCES work_target (id, kind))");
            exec(p, "CREATE TABLE t2_leave (id INT PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'LEAVE' CHECK (kind = 'LEAVE'), reason TEXT, FOREIGN KEY (id, kind) REFERENCES work_target (id, kind))");
            exec(p, "CREATE TABLE t2_entry (id SERIAL PRIMARY KEY, target_id INT NOT NULL REFERENCES work_target (id), hours INT)");
            exec(p, "INSERT INTO work_target (kind) VALUES ('CLIENT'), ('LEAVE')");
            exec(p, "INSERT INTO t2_client (id, name) VALUES (1, '客户项目 A')");
            exec(p, "INSERT INTO t2_leave (id, reason) VALUES (2, '年假')");
            out("super_table.ids", "共同父表 work_target 分配身份：" + q(p, "SELECT id, kind FROM work_target ORDER BY id") + "，两个目标的 id 不会相同");
            out("super_table.dangling", "工时引用不存在的目标 99：" + tryExec(p, "INSERT INTO t2_entry (target_id, hours) VALUES (99, 8)"));
            exec(p, "INSERT INTO t2_entry (target_id, hours) VALUES (1, 2)");
            out("super_table.delete", "删除仍被工时引用的目标 1：" + tryExec(p, "DELETE FROM t2_client WHERE id = 1; DELETE FROM work_target WHERE id = 1"));
            out("super_table.kind_mismatch", "把 LEAVE 目标 2 同时登记成客户项目：" + tryExec(p, "INSERT INTO t2_client (id, name) VALUES (2, '冒充')"));
            exec(p, "INSERT INTO work_target (kind) VALUES ('CLIENT')");
            out("super_table.missing_child", "只插入父表一行 (3, CLIENT)、不插子表：成功；父表里没有子类行的目标 " + q(p, "SELECT COUNT(*) FROM work_target w WHERE NOT EXISTS (SELECT 1 FROM t2_client c WHERE c.id = w.id) AND NOT EXISTS (SELECT 1 FROM t2_leave l WHERE l.id = w.id)").replaceAll("[()]", "") + " 个：「恰好有一个子类行」外键表达不了");
        }
    }

    static String compile(Path dir, String src) throws Exception {
        Path f = dir.resolve("WorkTarget.java");
        Files.writeString(f, src);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
        var fm = javac.getStandardFileManager(d, null, null);
        boolean ok = javac.getTask(null, fm, d, List.of("-d", dir.resolve("out").toString()), null, fm.getJavaFileObjects(f)).call();
        if (ok) return "编译通过";
        List<String> errs = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> x : d.getDiagnostics())
            if (x.getKind() == Diagnostic.Kind.ERROR) errs.add("第 " + x.getLineNumber() + " 行：" + x.getMessage(java.util.Locale.ROOT));
        return "编译失败，" + String.join("；", errs);
    }
}
