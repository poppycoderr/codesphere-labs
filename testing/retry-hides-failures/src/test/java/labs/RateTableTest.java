package labs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RateTableTest {
    @Test
    void usdRate() {
        assertEquals(712, RateTable.rate("USD"));
    }
}
