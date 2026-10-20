package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lab.Report.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 拆分后留在容器一侧的装配测试：真实容器里登记两个限定绑定，再按注入规则构造 Report */
class ContainerTest {
    @Test
    @DisplayName("真实容器按限定符分别装配 raw 与 daily")
    void wiresQualifiedBindings() {
        MiniContainer c = new MiniContainer();
        c.bind(Source.class, "raw", ctx -> (Source) () -> "raw");
        c.bind(Source.class, "daily", ctx -> (Source) () -> "daily");
        Report r = Report.inject(c);
        assertEquals("raw", r.raw().name());
        assertEquals("daily", r.daily().name());
    }
}
