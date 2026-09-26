package labs.lifecycle;

import java.util.ArrayList;
import java.util.List;

/** 按发生顺序记录生命周期回调。 */
public final class Journal {
    private static final List<String> STEPS = new ArrayList<>();

    private Journal() {
    }

    public static synchronized void add(String step) {
        STEPS.add(step);
    }

    public static synchronized List<String> drain() {
        List<String> out = List.copyOf(STEPS);
        STEPS.clear();
        return out;
    }
}
