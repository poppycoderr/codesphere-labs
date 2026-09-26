package labs.payment.sdk;

/** 代表第三方支付 SDK：它在 classpath 上，支付自动配置才生效。 */
public final class PaymentSdk {
    public static String version() {
        return "sdk-3.2";
    }
}
