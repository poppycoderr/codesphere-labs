package labs.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DomainEventsTest {
    @Autowired
    Repositories.Tickets tickets;
    @Autowired
    Repositories.LegacyTickets legacy;
    @Autowired
    TicketService service;
    @Autowired
    EventLog log;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void reset() {
        log.clear();
        jdbc.update("DELETE FROM audit");
    }

    @Test
    @Order(1)
    void saveRegisteredEventsPublishesAndClears() {
        Ticket t = tickets.save(Ticket.open("alice"));
        long first = log.count("TicketOpened");
        String where = log.sync.get(0);
        tickets.save(t);
        long second = log.count("TicketOpened");
        Facts.record("save.publish", "save 一个登记了 TicketOpened 的新聚合：发布 " + first + " 次，监听器收到时 " + where.replaceAll(".*（", "（")
                + "；没有新事件再 save 一次：累计仍是 " + second + " 次（发布后已清空）");
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
    }

    @Test
    @Order(2)
    void dirtyCheckingDoesNotPublish() {
        Long id = tickets.save(Ticket.open("bob")).getId();
        log.clear();
        service.closeWithoutSave(id, 5);
        String status = tickets.findById(id).orElseThrow().getStatus();
        long without = log.count("TicketClosed");
        Long id2 = tickets.save(Ticket.open("carol")).getId();
        log.clear();
        service.closeWithSave(id2, 4);
        long with = log.count("TicketClosed");
        Facts.record("save.dirty-checking", "close() 登记了 TicketClosed，但依赖脏检查没有调用 save：数据库里状态是 " + status + "，事件发布 " + without
                + " 次；显式调用 save：发布 " + with + " 次，提交后监听器收到 " + log.afterCommit.size() + " 次");
        assertThat(status).isEqualTo("CLOSED");
        assertThat(without).isZero();
        assertThat(with).isEqualTo(1);
    }

    @Test
    @Order(3)
    void domainEventsWithoutClearRepublish() {
        LegacyTicket t = legacy.save(new LegacyTicket("dave"));
        legacy.save(t);
        legacy.save(t);
        long n = log.count("TicketOpened");
        Facts.record("save.no-clear", "只有 @DomainEvents、没有 @AfterDomainEventPublication：同一个对象 save 三次，TicketOpened 发布 " + n + " 次");
        assertThat(n).isEqualTo(3);
    }

    @Test
    @Order(4)
    void constructorRegisteredEventsDuplicateOnUpdate() {
        service.naiveApply(100L, "erin");
        service.naiveClose(100L, 3);
        long applied = log.count("TicketApplied");
        long closed = log.count("TicketClosed");
        Facts.record("po.constructor", "PO 构造器里登记「已申请」，每次保存都 new 一个 PO：申请 1 次、关闭 1 次后，TicketApplied 发布 " + applied
                + " 次，TicketClosed " + closed + " 次（关闭工单被当成又申请了一次）");
        log.clear();
        service.apply(200L, "frank");
        service.close(200L, 3);
        long applied2 = log.count("TicketApplied");
        long closed2 = log.count("TicketClosed");
        Facts.record("po.domain", "事件由领域行为产生、映射时带过去：TicketApplied " + applied2 + " 次，TicketClosed " + closed2 + " 次");
        assertThat(applied).isEqualTo(2);
        assertThat(applied2).isEqualTo(1);
        assertThat(closed2).isEqualTo(1);
    }

    @Test
    @Order(5)
    void writesInAfterCommitListener() {
        service.auditPlain("plain");
        Integer plain = jdbc.queryForObject("SELECT COUNT(*) FROM audit WHERE note = 'plain'", Integer.class);
        service.auditRequiresNew("requires_new");
        Integer requiresNew = jdbc.queryForObject("SELECT COUNT(*) FROM audit WHERE note = 'requires_new'", Integer.class);
        Facts.record("after-commit.write", "AFTER_COMMIT 监听器里用 JdbcTemplate 插入一行：不开新事务时方法返回后查到 " + plain + " 行；监听器标注 REQUIRES_NEW 时查到 "
                + requiresNew + " 行");
        Facts.record("after-commit.dirty", "不开新事务的那次写入：" + (plain == 1 ? "被重置 autoCommit 的动作一并提交了（取决于连接清理顺序，不是契约）" : "没有被提交"));
        assertThat(requiresNew).isEqualTo(1);
    }
}
