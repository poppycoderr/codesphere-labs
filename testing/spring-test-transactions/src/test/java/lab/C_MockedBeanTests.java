package lab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 替换了一个 Bean：上下文的配置不同，缓存里的上下文不能复用 */
@SpringBootTest
class C_MockedBeanTests {
    @MockitoBean App.AuditLog audit;
    @Test void c1_contextStarts() { assertThat(audit).isNotNull(); }
}
