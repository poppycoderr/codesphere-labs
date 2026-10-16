package lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 同一个错误（购物车少了 banana），四种断言写法给出的失败信息 */
class FailureMessageTest {
    @Test void a_assertTrue_contains() { assertTrue(Shop.cartItems().contains("banana")); }
    @Test void b_assertEquals_list() { assertEquals(List.of("apple", "banana", "pear"), Shop.cartItems()); }
    @Test void c_assertj_contains() { assertThat(Shop.cartItems()).contains("banana"); }
    @Test void d_assertj_described() { assertThat(Shop.cartItems()).as("alice 的购物车").containsExactly("apple", "banana", "pear"); }
}
