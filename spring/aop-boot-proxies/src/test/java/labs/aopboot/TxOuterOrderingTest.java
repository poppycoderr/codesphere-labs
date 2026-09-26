package labs.aopboot;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "labs.order=tx-outer")
class TxOuterOrderingTest extends OrderingBase {
    @Override
    String label() {
        return "tx-outer";
    }
}
