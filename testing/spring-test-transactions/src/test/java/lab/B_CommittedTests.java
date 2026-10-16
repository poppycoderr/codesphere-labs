package lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 同样的断言，不加测试事务：每次服务调用都真正提交 */
@SpringBootTest @TestMethodOrder(MethodOrderer.MethodName.class)
class B_CommittedTests {
    @Autowired App.Shop shop; @Autowired App.Mailbox mailbox; @Autowired App.Audits audits;

    @Test void b0_auditTableEmptyAtStart() { assertThat(audits.count()).as("前一个测试类回滚之后，审计表里的行数").isZero(); }
    @Test void b1_sameEmailTwice_noError() {
        assertThatCode(() -> { shop.register("m-b1", "dup-b@example.com"); shop.register("m-b2", "dup-b@example.com"); }).doesNotThrowAnyException();
    }
    @Test void b2_linesReadableAfterLoad() {
        shop.placeOrder("o-b", "A", "B");
        assertThat(shop.load("o-b").lines).hasSize(2);
    }
    @Test void b3_mailSentAfterRegister() {
        shop.register("m-b3", "mail-b@example.com");
        assertThat(mailbox.sent).contains("mail-b@example.com");
    }
    @Test void b4_orderVisibleToAnotherThread() {
        shop.placeOrder("o-b4", "A");
        assertThat(shop.visibleFromAnotherThread("o-b4")).isTrue();
    }
}
