package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorResponderTest {
    @Test
    @DisplayName("查找映射器失败时回落到 500")
    void lookupFailure() {
        ErrorResponder r = new ErrorResponder(e -> { throw new IllegalStateException("没有映射器"); });
        assertEquals(500, r.respond(new RuntimeException("x")));
    }

    /** 名字说的是映射器执行失败，桩却让查找失败：和上一个测试走的是同一条路径 */
    @Test
    @DisplayName("映射器执行失败时回落到 500（名不副实：实际让查找失败）")
    void mapperFailureMisnamed() {
        ErrorResponder r = new ErrorResponder(e -> { throw new IllegalStateException("映射器坏了"); });
        assertEquals(500, r.respond(new RuntimeException("x")));
    }

    @Test
    @DisplayName("映射器执行失败时回落到 500（真正让映射器抛异常）")
    void mapperFailure() {
        ErrorResponder r = new ErrorResponder(e -> ex -> { throw new IllegalStateException("映射器坏了"); });
        assertEquals(500, r.respond(new RuntimeException("x")));
    }
}
