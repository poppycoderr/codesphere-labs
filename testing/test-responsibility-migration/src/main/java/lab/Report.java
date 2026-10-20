package lab;

/** 一份报表依赖两个数据源：原始数据与汇总数据，都是 Source，只靠限定符区分 */
public record Report(Source raw, Source daily) {
    public interface Source { String name(); }

    /** 注入规则：构造 Report 时向 Context 要 raw 与 daily 两个限定绑定 */
    public static Report inject(Context ctx) {
        return new Report((Source) ctx.get(Source.class, "raw"), (Source) ctx.get(Source.class, "daily"));
    }
}
