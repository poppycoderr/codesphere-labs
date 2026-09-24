package labs.ddd.layered.domain;

/** 手机号值对象，只接受 11 位中国大陆手机号。 */
public record Phone(String value) {
    public Phone {
        if (value == null || !value.matches("1[3-9]\\d{9}")) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
    }
}
