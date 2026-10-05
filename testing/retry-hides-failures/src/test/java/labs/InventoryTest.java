package labs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** 两个测试用了同一个 SKU：单独运行都通过，按名字顺序一起运行时第二个失败。 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class InventoryTest {
    @Test
    void a_restock() {
        Inventory.add("sku-1", 5);
        assertEquals(5, Inventory.count("sku-1"));
    }

    @Test
    void b_firstDelivery() {
        Inventory.add("sku-1", 3);
        assertEquals(3, Inventory.count("sku-1"));
    }
}
