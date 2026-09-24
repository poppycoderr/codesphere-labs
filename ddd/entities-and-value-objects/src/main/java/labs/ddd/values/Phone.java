package labs.ddd.values;

import java.util.regex.Pattern;

/** 手机号值对象：接受常见写法，内部只保存一种规范形式，因此可以直接按值比较。 */
public record Phone(String e164) {

    private static final Pattern MAINLAND = Pattern.compile("1[3-9]\\d{9}");

    public Phone {
        if (e164 == null || !e164.startsWith("+86") || !MAINLAND.matcher(e164.substring(3)).matches()) {
            throw new IllegalArgumentException("不是中国大陆手机号：" + e164);
        }
    }

    public static Phone parse(String raw) {
        String digits = raw.replaceAll("[\\s-]", "");
        if (digits.startsWith("+86")) {
            digits = digits.substring(3);
        }
        if (!MAINLAND.matcher(digits).matches()) {
            throw new IllegalArgumentException("不是中国大陆手机号：" + raw);
        }
        return new Phone("+86" + digits);
    }
}
