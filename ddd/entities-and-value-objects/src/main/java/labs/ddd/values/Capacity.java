package labs.ddd.values;

/** 场次容量：至少 1 人。 */
public record Capacity(int value) {

    public Capacity {
        if (value < 1) {
            throw new IllegalArgumentException("容量至少为 1：" + value);
        }
    }
}
