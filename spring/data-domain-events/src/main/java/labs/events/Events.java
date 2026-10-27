package labs.events;

/** 实验中的领域事件。 */
public final class Events {
    private Events() {
    }

    public record TicketOpened(String customer) {}

    public record TicketClosed(String customer, int rating) {}

    public record TicketApplied(String customer) {}

    public record AuditPlain(String note) {}

    public record AuditRequiresNew(String note) {}
}
