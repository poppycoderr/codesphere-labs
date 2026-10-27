package labs.events;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.springframework.data.domain.AbstractAggregateRoot;

/** 业务行为里登记事件，由仓储 save 时发布并清空。 */
@Entity
public class Ticket extends AbstractAggregateRoot<Ticket> {
    @Id
    @GeneratedValue
    private Long id;
    private String customer;
    private String status;
    private Integer rating;

    protected Ticket() {
    }

    public static Ticket open(String customer) {
        Ticket t = new Ticket();
        t.customer = customer;
        t.status = "OPEN";
        t.registerEvent(new Events.TicketOpened(customer));
        return t;
    }

    public void close(int rating) {
        this.status = "CLOSED";
        this.rating = rating;
        registerEvent(new Events.TicketClosed(customer, rating));
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
