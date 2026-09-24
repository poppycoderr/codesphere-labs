package labs.ddd.layered.observability;

import java.util.ArrayList;
import java.util.List;

/** 记录一次用例经过了哪些层。不属于任何一层，架构规则不检查它；领域层不使用它。 */
public final class Trace {
    private static final List<String> STEPS = new ArrayList<>();

    private Trace() {
    }

    public static synchronized void step(String layer, String what) {
        STEPS.add(layer + "\t" + what);
    }

    public static synchronized List<String> drain() {
        List<String> out = List.copyOf(STEPS);
        STEPS.clear();
        return out;
    }
}
