package labs.events;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.domain.AbstractAggregateRoot;

/**
 * 持久化对象（PO）。naive 构造器在「创建 PO」时登记「已申请」事件——把持久化映射当成了业务发生。
 */
@Entity
public class TicketRecord extends AbstractAggregateRoot<TicketRecord> {
    @Id
    private Long id;
    private String customer;
    private String status;

    protected TicketRecord() {
    }

    TicketRecord(Long id, String customer, String status, boolean registerAppliedInConstructor) {
        this.id = id;
        this.customer = customer;
        this.status = status;
        if (registerAppliedInConstructor) {
            registerEvent(new Events.TicketApplied(customer));
        }
    }

    TicketRecord withEvent(Object event) {
        registerEvent(event);
        return this;
    }

    Long getId() {
        return id;
    }

    String getCustomer() {
        return customer;
    }

    String getStatus() {
        return status;
    }
}
