package lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** 每个测试包在一个测试事务里，结束时回滚：这是很多项目的默认写法 */
@SpringBootTest @Transactional @TestMethodOrder(MethodOrderer.MethodName.class)
class A_TransactionalTests {
    @Autowired App.Shop shop; @Autowired App.Mailbox mailbox;

    @Test void a1_sameEmailTwice_noError() {
        assertThatCode(() -> { shop.register("m-a1", "dup-a@example.com"); shop.register("m-a2", "dup-a@example.com"); }).doesNotThrowAnyException();
    }
    @Test void a2_linesReadableAfterLoad() {
        shop.placeOrder("o-a", "A", "B");
        assertThat(shop.load("o-a").lines).hasSize(2);
    }
    @Test void a3_mailSentAfterRegister() {
        shop.register("m-a3", "mail-a@example.com");
        assertThat(mailbox.sent).contains("mail-a@example.com");
    }
    @Test void a4_orderVisibleToAnotherThread() {
        shop.placeOrder("o-a4", "A");
        assertThat(shop.visibleFromAnotherThread("o-a4")).isTrue();
    }
}
