package lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 仓储接口的契约测试：替身和真实实现跑同一组用例 */
abstract class RepositoryContract {
    abstract Orders.OrderRepository repo();

    @Test void byCustomer_newestFirst() {
        var r = repo();
        r.insert(new Orders.Order("o-1", "c-1", Instant.parse("2026-10-01T00:00:00Z"), "NEW"));
        r.insert(new Orders.Order("o-2", "c-1", Instant.parse("2026-10-03T00:00:00Z"), "NEW"));
        r.insert(new Orders.Order("o-3", "c-1", Instant.parse("2026-10-02T00:00:00Z"), "NEW"));
        assertThat(r.byCustomer("c-1")).extracting(Orders.Order::id).containsExactly("o-2", "o-3", "o-1");
    }
    @Test void insert_duplicateId_rejected() {
        var r = repo();
        r.insert(new Orders.Order("o-1", "c-1", Instant.parse("2026-10-01T00:00:00Z"), "NEW"));
        assertThatThrownBy(() -> r.insert(new Orders.Order("o-1", "c-2", Instant.parse("2026-10-02T00:00:00Z"), "PAID")))
                .isInstanceOf(IllegalStateException.class);
    }
}
