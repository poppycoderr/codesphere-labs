package labs.txattr;

import org.springframework.data.jpa.repository.JpaRepository;

/** Item 仓储。 */
public interface ItemRepository extends JpaRepository<Item, Long> {
}
