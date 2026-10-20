package lab;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** 最小容器：按「类型 + 限定符」登记工厂，取值时交给工厂并传入自身作为 Context */
public class MiniContainer implements Context {
    private final Map<String, Function<Context, Object>> factories = new HashMap<>();

    public void bind(Class<?> type, String qualifier, Function<Context, Object> factory) {
        // 变异 ignoreQualifier：登记时丢掉限定符，同类型的绑定互相覆盖
        String q = Mutant.is("ignoreQualifier") ? "" : qualifier;
        factories.put(type.getName() + "@" + q, factory);
    }

    @Override
    public Object get(Class<?> type, String qualifier) {
        String q = Mutant.is("ignoreQualifier") ? "" : qualifier;
        Function<Context, Object> f = factories.get(type.getName() + "@" + q);
        if (f == null) throw new IllegalStateException("缺少依赖：" + type.getSimpleName() + "@" + qualifier);
        return f.apply(this);
    }
}
