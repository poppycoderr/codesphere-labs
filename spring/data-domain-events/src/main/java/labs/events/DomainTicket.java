package labs.events;

import java.util.ArrayList;
import java.util.List;

/** 不依赖框架的领域对象：业务行为产生待发布事件。 */
public class DomainTicket {
    final Long id;
    final String customer;
    String status;
    final List<Object> pending = new ArrayList<>();

    private DomainTicket(Long id, String customer, String status) {
        this.id = id;
        this.customer = customer;
        this.status = status;
    }

    static DomainTicket apply(Long id, String customer) {
        DomainTicket t = new DomainTicket(id, customer, "APPLIED");
        t.pending.add(new Events.TicketApplied(customer));
        return t;
    }

    static DomainTicket rehydrate(Long id, String customer, String status) {
        return new DomainTicket(id, customer, status);
    }

    void close(int rating) {
        status = "CLOSED";
        pending.add(new Events.TicketClosed(customer, rating));
    }
}
