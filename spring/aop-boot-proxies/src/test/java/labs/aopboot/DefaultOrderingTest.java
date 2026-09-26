package labs.aopboot;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "labs.order=default")
class DefaultOrderingTest extends OrderingBase {
    @Override
    String label() {
        return "default";
    }
}
