package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import lab.Report.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 从容器测试拆出来的注入规则测试：用桩 Context 只检查「向容器要了什么」 */
class InjectionRuleTest {
    @Test
    @DisplayName("注入规则向 Context 要 Source@raw 与 Source@daily")
    void asksForQualifiedSources() {
        List<String> asked = new ArrayList<>();
        Context stub = (type, q) -> { asked.add(type.getSimpleName() + "@" + q); return (Source) () -> q; };
        Report r = Report.inject(stub);
        assertEquals(List.of("Source@raw", "Source@daily"), asked);
        assertEquals("daily", r.daily().name());
    }
}
