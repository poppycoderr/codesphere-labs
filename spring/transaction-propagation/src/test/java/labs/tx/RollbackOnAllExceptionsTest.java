package labs.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.RollbackOn;

/** 声明 @EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS) 后，受检异常也回滚。 */
@SpringBootTest
class RollbackOnAllExceptionsTest extends MySqlBase {

    @TestConfiguration
    @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
    static class AllExceptions {
    }

    @Autowired CheckoutService checkout;

    @Test
    void checkedExceptionRollsBack() {
        assertThrows(CheckoutService.PaymentRejected.class, () -> checkout.confirmWithCheckedFailure(7));
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE id = 7"));
        Facts.record("rollbackOn.all", "rollbackOn = ALL_EXCEPTIONS 时抛出受检异常：事务回滚，订单行数 0");
    }
}
