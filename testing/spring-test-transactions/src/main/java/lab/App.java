package lab;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** 一个小应用：用户注册（邮箱唯一）、订单与懒加载的订单行、提交后发送通知、新事务写审计、另一个线程读订单 */
@SpringBootApplication
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(considerNestedRepositories = true)
public class App {
    public static void main(String[] args) { SpringApplication.run(App.class, args); }

    @Entity(name = "Member") @Table(uniqueConstraints = @UniqueConstraint(columnNames = "email"))
    public static class Member { @Id public String id; public String email; public Member() { } public Member(String id, String e) { this.id = id; email = e; } }
    public interface Members extends JpaRepository<Member, String> { }

    @Entity(name = "PurchaseOrder")
    public static class PurchaseOrder {
        @Id public String id;
        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL) public List<OrderLine> lines = new ArrayList<>();
        public PurchaseOrder() { } public PurchaseOrder(String id) { this.id = id; }
    }
    @Entity(name = "OrderLine")
    public static class OrderLine { @Id @GeneratedValue public Long id; @ManyToOne public PurchaseOrder order; public String sku; public OrderLine() { } public OrderLine(PurchaseOrder o, String s) { order = o; sku = s; } }
    public interface Orders extends JpaRepository<PurchaseOrder, String> { }

    @Entity(name = "Audit")
    public static class Audit { @Id @GeneratedValue public Long id; public String what; public Audit() { } public Audit(String w) { what = w; } }
    public interface Audits extends JpaRepository<Audit, Long> { }

    public record Registered(String email) { }
    /** 「发出去的邮件」：提交之后才发送 */
    @Component public static class Mailbox {
        public final List<String> sent = new CopyOnWriteArrayList<>();
        @TransactionalEventListener public void on(Registered e) { sent.add(e.email()); }
    }

    @Service public static class AuditLog {
        private final Audits audits;
        public AuditLog(Audits a) { audits = a; }
        @Transactional(propagation = Propagation.REQUIRES_NEW) public void write(String what) { audits.save(new Audit(what)); }
    }

    @Service public static class Shop {
        public static final AtomicInteger CONSTRUCTED = new AtomicInteger();   // 每创建一个应用上下文加一
        private final Members members; private final Orders orders; private final ApplicationEventPublisher events; private final AuditLog audit;
        public Shop(Members m, Orders o, ApplicationEventPublisher e, AuditLog a) { members = m; orders = o; events = e; audit = a; CONSTRUCTED.incrementAndGet(); }

        @Transactional public void register(String id, String email) { members.save(new Member(id, email)); events.publishEvent(new Registered(email)); audit.write("register " + email); }

        @Transactional public void placeOrder(String id, String... skus) { PurchaseOrder o = new PurchaseOrder(id); for (String s : skus) o.lines.add(new OrderLine(o, s)); orders.save(o); }
        @Transactional(readOnly = true) public PurchaseOrder load(String id) { return orders.findById(id).orElseThrow(); }

        /** 在另一个线程里读订单（例如异步处理、消息消费者） */
        public boolean visibleFromAnotherThread(String id) { return CompletableFuture.supplyAsync(() -> orders.existsById(id)).join(); }
    }
}
