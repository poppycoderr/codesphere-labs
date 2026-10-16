import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 同一个人在平台上有几种角色（讲师、学员）。三种建模方式：一个大实体加布尔标志、一个类实现多个角色接口、参与方加角色对象。
 * 比较：角色撤销后遗留的数据、接口视图能否限制调用方、角色的历史与有效期、同一角色的规则放在哪里。所有输入固定。
 */
public class RoleLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    // ---------- 写法一：一个大实体 ----------
    static class Member {
        String name; boolean instructor; boolean learner; boolean admin;
        String bio; Integer hourlyFee;          // 只对讲师有意义
        String level;                            // 只对学员有意义
        Member(String n) { name = n; }
        int nullableFieldsForLearnerOnly() { int c = 0; if (bio == null) c++; if (hourlyFee == null) c++; return c; }
    }

    // ---------- 写法二：一个类实现多个角色接口 ----------
    interface Learner { String name(); void enroll(String course); }
    interface Instructor { String name(); void publish(String course); }
    static class Person implements Learner, Instructor {
        final String name; boolean admin; final List<String> log = new ArrayList<>();
        Person(String n) { name = n; }
        public String name() { return name; }
        public void enroll(String c) { log.add("enroll " + c); }
        public void publish(String c) { log.add("publish " + c); }
        public void grantAdmin() { admin = true; }
    }
    /** 只应该接触「学员」能力的代码 */
    static void learnerOnlyCode(Learner l) {
        l.enroll("Java 基础");
        if (l instanceof Person p) p.grantAdmin();     // 一次强制转换就拿到了全部能力
    }

    // ---------- 写法三：参与方 + 角色对象 ----------
    record Party(String id, String name) { }
    sealed interface Role permits InstructorRole, LearnerRole { Party party(); LocalDate from(); Optional<LocalDate> to(); }
    record InstructorRole(Party party, LocalDate from, Optional<LocalDate> to, int hourlyFee) implements Role {
        InstructorRole {
            if (hourlyFee <= 0) throw new IllegalArgumentException("讲师课时费必须为正：" + hourlyFee);
        }
    }
    record LearnerRole(Party party, LocalDate from, Optional<LocalDate> to, String level) implements Role { }
    static boolean activeOn(Role r, LocalDate d) { return !d.isBefore(r.from()) && r.to().map(d::isBefore).orElse(true); }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、大实体：角色撤销后数据还在，规则靠 if
        Member m = new Member("alice");
        m.instructor = true; m.bio = "十年后端"; m.hourlyFee = 500;
        m.instructor = false;                                // 撤销讲师身份
        out("big.revoke", "大实体撤销讲师：instructor=false，但 bio=" + m.bio + "、hourlyFee=" + m.hourlyFee + " 仍在；按「有课时费」统计讲师会把她算进去");
        Member learnerOnly = new Member("bob"); learnerOnly.learner = true; learnerOnly.level = "初级";
        out("big.nullable", "只是学员的 bob：讲师字段有 " + learnerOnly.nullableFieldsForLearnerOnly() + " 个为 null；「讲师必须有课时费」只能写成 if (instructor && hourlyFee == null)，在每个修改入口重复");
        out("big.history", "撤销后再问「alice 9 月份是不是讲师」：只有当前的布尔值 " + m.instructor + "，历史没有了");

        // 二、接口视图：编译期的限制，运行时一次转换就失效
        Person carol = new Person("carol");
        learnerOnlyCode(carol);
        out("iface.cast", "把 carol 作为 Learner 传给只该用学员能力的代码：它 instanceof 转回 Person 并调用 grantAdmin()，admin=" + carol.admin + "；接口是视图，不是权限边界");
        out("iface.same_object", "Learner 与 Instructor 是同一个对象：" + (((Learner) carol) == ((Instructor) carol)) + "；两个角色共享一份状态和一个生命周期，撤销讲师角色只能靠再加一个标志");

        // 三、参与方 + 角色对象：角色有自己的有效期、规则和历史
        Party alice = new Party("p-1", "alice");
        List<Role> roles = new ArrayList<>();
        roles.add(new LearnerRole(alice, LocalDate.of(2026, 1, 1), Optional.empty(), "中级"));
        roles.add(new InstructorRole(alice, LocalDate.of(2026, 3, 1), Optional.of(LocalDate.of(2026, 10, 1)), 500));
        roles.add(new InstructorRole(alice, LocalDate.of(2027, 1, 1), Optional.empty(), 600));
        LocalDate sep = LocalDate.of(2026, 9, 15), nov = LocalDate.of(2026, 11, 15);
        out("role.history", "alice 在 9 月 15 日是讲师：" + roles.stream().anyMatch(r -> r instanceof InstructorRole && activeOn(r, sep))
                + "；11 月 15 日：" + roles.stream().anyMatch(r -> r instanceof InstructorRole && activeOn(r, nov))
                + "；讲师角色共 " + roles.stream().filter(r -> r instanceof InstructorRole).count() + " 段，课时费分别是 500、600");
        String bad;
        try { new InstructorRole(alice, LocalDate.of(2027, 2, 1), Optional.empty(), 0); bad = "成功"; } catch (IllegalArgumentException e) { bad = "被拒绝（" + e.getMessage() + "）"; }
        out("role.invariant", "讲师的规则放在讲师角色自己的构造器里，创建课时费为 0 的讲师角色：" + bad + "；学员角色不需要这条检查");
        out("role.count", "字段分布：大实体 1 个类 " + Member.class.getDeclaredFields().length + " 个字段；角色对象 1 个参与方（" + Party.class.getRecordComponents().length + " 个字段）+ 2 种角色（各自 "
                + InstructorRole.class.getRecordComponents().length + "、" + LearnerRole.class.getRecordComponents().length + " 个字段）");
    }
}
