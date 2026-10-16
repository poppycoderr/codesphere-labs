import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.stat.Statistics;

/**
 * 领域模型里的一个集合字段，背后是对数据库的一次或多次查询。用 Hibernate 的统计计数，
 * 看「名额检查用 size()」「往集合里加一项」「遍历多个聚合的集合」「清空集合」各执行了多少条 SQL、加载了多少个对象。
 */
public class CollectionLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    @Entity(name = "Session")
    public static class EventSession {
        @Id @GeneratedValue Long id;
        int capacity;
        int registeredCount;                                   // 方案三：计数器
        @Version int version;
        @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
        List<Registration> registrations = new ArrayList<>();  // List 映射为 bag
        @OneToMany(mappedBy = "session2", cascade = CascadeType.ALL)
        Set<Waitlist> waitlist = new HashSet<>();              // Set
        public EventSession() { }
        EventSession(int capacity) { this.capacity = capacity; }

        /** 方案一：在聚合里用集合的大小检查名额 */
        void registerBySize(String attendee) {
            if (registrations.size() >= capacity) throw new IllegalStateException("名额已满");
            Registration r = new Registration(this, attendee); registrations.add(r);
        }
        /** 方案三：用计数器检查名额，集合不加载 */
        void registerByCounter(String attendee) {
            if (registeredCount >= capacity) throw new IllegalStateException("名额已满");
            registeredCount++; registrations.add(new Registration(this, attendee));
        }
    }
    @Entity(name = "Registration")
    public static class Registration {
        @Id @GeneratedValue Long id;
        @ManyToOne EventSession session;
        String attendee;
        public Registration() { }
        Registration(EventSession s, String a) { session = s; attendee = a; }
    }
    @Entity(name = "Waitlist")
    public static class Waitlist {
        @Id @GeneratedValue Long id;
        @ManyToOne EventSession session2;
        String attendee;
        public Waitlist() { }
        Waitlist(EventSession s, String a) { session2 = s; attendee = a; }
    }

    static SessionFactory sf;
    static Statistics st;
    /** 在新的会话和事务里执行，返回执行的 SQL 条数与加载的实体数 */
    static String run(Consumer<Session> work) {
        st.clear();
        try (Session s = sf.openSession()) {
            var tx = s.beginTransaction();
            work.accept(s);
            tx.commit();
        }
        return "SQL " + st.getPrepareStatementCount() + " 条，加载实体 " + st.getEntityLoadCount() + " 个";
    }

    public static void main(String[] args) {
        sf = new Configuration()
                .addAnnotatedClass(EventSession.class).addAnnotatedClass(Registration.class).addAnnotatedClass(Waitlist.class)
                .setProperty("hibernate.connection.url", "jdbc:postgresql://postgres:5432/labs")
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "example_password")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true")
                .setProperty("hibernate.show_sql", "false")
                .buildSessionFactory();
        st = sf.getStatistics();
        out("env", "java.version=" + System.getProperty("java.version") + " hibernate=" + org.hibernate.Version.getVersionString());

        // 准备：1 个场次 1000 人报名、1000 人候补；另有 50 个场次各 3 人报名
        long[] ids = new long[1];
        run(s -> {
            EventSession e = new EventSession(2000);
            for (int i = 0; i < 1000; i++) { e.registrations.add(new Registration(e, "a" + i)); e.waitlist.add(new Waitlist(e, "w" + i)); }
            e.registeredCount = 1000;
            s.persist(e); ids[0] = 0;
            for (int k = 0; k < 50; k++) { EventSession x = new EventSession(10); for (int i = 0; i < 3; i++) x.registrations.add(new Registration(x, "b" + i)); x.registeredCount = 3; s.persist(x); }
        });
        long big = sf.fromSession(s -> s.createQuery("select id from Session where capacity = 2000", Long.class).getSingleResult());

        out("size_check", "1000 人的场次，聚合方法里用 registrations.size() 检查名额后报名：" + run(s -> s.find(EventSession.class, big).registerBySize("new1")));
        out("counter_check", "改用场次上的计数器检查名额：" + run(s -> s.find(EventSession.class, big).registerByCounter("new2")));
        out("bag_add", "不检查名额，只往 List（bag）里加一项：" + run(s -> { EventSession e = s.find(EventSession.class, big); e.registrations.add(new Registration(e, "new3")); }));
        int v0 = sf.fromSession(s -> s.find(EventSession.class, big).version);
        run(s -> { EventSession e = s.find(EventSession.class, big); e.registrations.add(new Registration(e, "new4")); });
        int v1 = sf.fromSession(s -> s.find(EventSession.class, big).version);
        run(s -> s.find(EventSession.class, big).registerByCounter("new5"));
        int v2 = sf.fromSession(s -> s.find(EventSession.class, big).version);
        out("version", "场次的 @Version：只往 mappedBy 的集合里加一项前后 " + v0 + " → " + v1 + "；同时修改计数器前后 " + v1 + " → " + v2 + "：只改子项时，根的版本号不变，乐观锁挡不住两个并发的报名");
        out("set_add", "往 Set 映射的候补里加一项：" + run(s -> { EventSession e = s.find(EventSession.class, big); e.waitlist.add(new Waitlist(e, "w-new")); }));
        out("count_query", "用查询回答「报了多少人」：" + run(s -> s.createQuery("select count(r) from Registration r where r.session.id = :id", Long.class).setParameter("id", big).getSingleResult()));

        out("n_plus_one", "查出 50 个小场次，逐个读 registrations.size()：" + run(s -> s.createQuery("from Session where capacity = 10", EventSession.class).getResultList().forEach(e -> e.registrations.size())));
        out("join_fetch", "同样的遍历，查询时 join fetch 集合：" + run(s -> s.createQuery("select distinct e from Session e join fetch e.registrations where e.capacity = 10", EventSession.class).getResultList().forEach(e -> e.registrations.size())));

        out("clear_orphans", "orphanRemoval 的集合 clear()（1005 项）：" + run(s -> s.find(EventSession.class, big).registrations.clear()));
        out("after_clear", "清空后报名表里这个场次剩下 " + sf.fromSession(s -> s.createQuery("select count(r) from Registration r where r.session.id = :id", Long.class).setParameter("id", big).getSingleResult()) + " 条");
        out("bulk_delete", "50 个小场次的报名用一条批量删除：" + run(s -> s.createMutationQuery("delete from Registration r where r.session.id in (select e.id from Session e where e.capacity = 10)").executeUpdate()));
        sf.close();
    }
}
