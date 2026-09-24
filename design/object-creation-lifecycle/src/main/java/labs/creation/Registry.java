package labs.creation;

import java.util.concurrent.atomic.AtomicInteger;

/** 经典的静态单例：INSTANCE 只在「加载它的那个类加载器」里唯一。 */
public final class Registry {
    public static final Registry INSTANCE = new Registry();
    private final AtomicInteger registered = new AtomicInteger();

    private Registry() {
    }

    public int register() {
        return registered.incrementAndGet();
    }
}
