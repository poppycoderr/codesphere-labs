package labs.events;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.DomainEvents;

/** 只写了 @DomainEvents、没有 @AfterDomainEventPublication 清空方法。 */
@Entity
public class LegacyTicket {
    @Id
    @GeneratedValue
    private Long id;
    private String customer;
    @Transient
    private final List<Object> events = new ArrayList<>();

    protected LegacyTicket() {
    }

    public LegacyTicket(String customer) {
        this.customer = customer;
        events.add(new Events.TicketOpened(customer));
    }

    @DomainEvents
    List<Object> events() {
        return events;
    }
}
