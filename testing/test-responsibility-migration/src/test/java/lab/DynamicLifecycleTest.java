package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** 动态测试共享的是工厂方法那一次的 @BeforeEach：每个动态子测试都往同一个列表里加东西 */
class DynamicLifecycleTest {
    static int beforeEachRuns;
    List<String> received;

    @BeforeEach
    void freshList() { beforeEachRuns++; received = new ArrayList<>(); }

    @TestFactory
    @DisplayName("三个动态子测试，各自期望列表里只有自己的一项")
    Stream<DynamicTest> eachExpectsFreshState() {
        return Stream.of("a", "b", "c").map(x -> DynamicTest.dynamicTest("子测试 " + x, () -> {
            received.add(x);
            assertEquals(List.of(x), received);
        }));
    }

    @Test
    @DisplayName("普通测试：每次都有新的列表")
    void plainTest() {
        received.add("p");
        assertEquals(List.of("p"), received);
    }

    @AfterAll
    static void note() throws Exception {
        Files.createDirectories(Path.of("target/lab-notes"));
        Files.writeString(Path.of("target/lab-notes/before_each_runs.txt"), "@BeforeEach 共执行 " + beforeEachRuns + " 次（1 个普通测试 + 1 个测试工厂，工厂产生了 3 个动态子测试）");
    }
}
