package lab;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 最后运行：记录整个测试过程中创建了几个应用上下文 */
@SpringBootTest
class Z_ContextCountTest {
    @Test void z1_record() throws Exception {
        Files.createDirectories(Path.of("target/lab-notes"));
        Files.writeString(Path.of("target/lab-notes/contexts.txt"), "A、B、Z 三个测试类配置相同，C 替换了一个 Bean；整个测试过程中创建的应用上下文：" + App.Shop.CONSTRUCTED.get() + " 个");
    }
}
