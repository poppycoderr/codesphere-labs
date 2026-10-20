package lab;

import java.util.function.Function;

/** 把异常转成 HTTP 状态：先查找映射器，再调用映射器；任一步失败都回落到 500 */
public class ErrorResponder {
    public interface Mapper { int toStatus(Exception e); }

    private final Function<Exception, Mapper> lookup;

    public ErrorResponder(Function<Exception, Mapper> lookup) { this.lookup = lookup; }

    public int respond(Exception e) {
        Mapper m;
        try { m = lookup.apply(e); } catch (RuntimeException lookupFailed) { return 500; }
        // 变异 noMapperFallback：映射器自身抛异常时不再回落，直接向外抛
        if (Mutant.is("noMapperFallback")) return m.toStatus(e);
        try { return m.toStatus(e); } catch (RuntimeException mapperFailed) { return 500; }
    }
}
