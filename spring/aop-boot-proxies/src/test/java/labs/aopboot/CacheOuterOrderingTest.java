package labs.aopboot;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "labs.order=cache-outer")
class CacheOuterOrderingTest extends OrderingBase {
    @Override
    String label() {
        return "cache-outer";
    }
}
