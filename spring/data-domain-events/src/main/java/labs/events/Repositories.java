package labs.events;

import org.springframework.data.jpa.repository.JpaRepository;

/** 仓储接口。 */
final class Repositories {
    private Repositories() {
    }

    interface Tickets extends JpaRepository<Ticket, Long> {
    }

    interface LegacyTickets extends JpaRepository<LegacyTicket, Long> {
    }

    interface Records extends JpaRepository<TicketRecord, Long> {
    }
}
