package lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 服务用客户 id 查订单（缺陷）。宽泛的 any() 让测试通过；精确参数 + 严格桩让测试失败 */
@ExtendWith(MockitoExtension.class)
class MatcherTest {
    static final Orders.Order O1 = new Orders.Order("o-1", "c-9", Instant.parse("2026-10-01T00:00:00Z"), "NEW");

    @Mock Orders.OrderRepository repo;

    @Test @MockitoSettings(strictness = Strictness.LENIENT)
    void a_anyMatcher_lenient() {
        when(repo.find(any())).thenReturn(Optional.of(O1));
        assertThat(new Orders.CancelService(repo).cancel("o-1", "c-9")).isEqualTo("CANCELLED");
    }

    @Test @MockitoSettings(strictness = Strictness.LENIENT)
    void b_exactArgument_lenient() {
        when(repo.find("o-1")).thenReturn(Optional.of(O1));
        assertThat(new Orders.CancelService(repo).cancel("o-1", "c-9")).isEqualTo("CANCELLED");
    }

    @Test
    void c_exactArgument_strictStubs() {
        when(repo.find("o-1")).thenReturn(Optional.of(O1));
        assertThat(new Orders.CancelService(repo).cancel("o-1", "c-9")).isEqualTo("CANCELLED");
    }
}
