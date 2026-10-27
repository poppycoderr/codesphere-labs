package labs.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：几种发布事件的方式。 */
@Service
public class TicketService {
    final Repositories.Tickets tickets;
    final Repositories.Records records;
    final ApplicationEventPublisher publisher;

    TicketService(Repositories.Tickets tickets, Repositories.Records records, ApplicationEventPublisher publisher) {
        this.tickets = tickets;
        this.records = records;
        this.publisher = publisher;
    }

    @Transactional
    public void closeWithSave(Long id, int rating) {
        Ticket t = tickets.findById(id).orElseThrow();
        t.close(rating);
        tickets.save(t);
    }

    /** 依赖脏检查自动更新，没有调用 save。 */
    @Transactional
    public void closeWithoutSave(Long id, int rating) {
        tickets.findById(id).orElseThrow().close(rating);
    }

    /** 朴素映射：每次保存都 new 一个 PO，PO 构造器登记「已申请」。 */
    @Transactional
    public void naiveApply(Long id, String customer) {
        DomainTicket d = DomainTicket.apply(id, customer);
        records.save(new TicketRecord(d.id, d.customer, d.status, true));
    }

    @Transactional
    public void naiveClose(Long id, int rating) {
        TicketRecord r = records.findById(id).orElseThrow();
        DomainTicket d = DomainTicket.rehydrate(r.getId(), r.getCustomer(), r.getStatus());
        d.close(rating);
        records.save(new TicketRecord(d.id, d.customer, d.status, true));
    }

    /** 事件来自领域行为，映射只负责把待发布事件带过去。 */
    @Transactional
    public void apply(Long id, String customer) {
        DomainTicket d = DomainTicket.apply(id, customer);
        records.save(toRecord(d));
    }

    @Transactional
    public void close(Long id, int rating) {
        TicketRecord r = records.findById(id).orElseThrow();
        DomainTicket d = DomainTicket.rehydrate(r.getId(), r.getCustomer(), r.getStatus());
        d.close(rating);
        records.save(toRecord(d));
    }

    private TicketRecord toRecord(DomainTicket d) {
        TicketRecord r = new TicketRecord(d.id, d.customer, d.status, false);
        d.pending.forEach(r::withEvent);
        d.pending.clear();
        return r;
    }

    @Transactional
    public void auditPlain(String note) {
        publisher.publishEvent(new Events.AuditPlain(note));
    }

    @Transactional
    public void auditRequiresNew(String note) {
        publisher.publishEvent(new Events.AuditRequiresNew(note));
    }
}
