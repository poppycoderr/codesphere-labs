import java.util.*;

public class Inherit {
    /** 继承复用：想统计「一共加过多少个元素」 */
    static class CountingSet<E> extends HashSet<E> {
        int added;
        @Override public boolean add(E e) { added++; return super.add(e); }
        @Override public boolean addAll(Collection<? extends E> c) { added += c.size(); return super.addAll(c); }
    }
    /** 组合：包装一个 Set，只依赖它的公开契约 */
    static class CountingSet2<E> {
        private final Set<E> delegate;
        private int added;
        CountingSet2(Set<E> delegate) { this.delegate = delegate; }
        boolean add(E e) { added++; return delegate.add(e); }
        boolean addAll(Collection<? extends E> c) { added += c.size(); return delegate.addAll(c); }
        int added() { return added; }
    }
    public static void main(String[] a) {
        CountingSet<String> s1 = new CountingSet<>();
        s1.addAll(List.of("a", "b", "c"));
        System.out.println("继承 HashSet：addAll 3 个元素后 added = " + s1.added);
        CountingSet2<String> s2 = new CountingSet2<>(new HashSet<>());
        s2.addAll(List.of("a", "b", "c"));
        System.out.println("组合 HashSet：addAll 3 个元素后 added = " + s2.added());
        CountingSet2<String> s3 = new CountingSet2<>(new TreeSet<>());
        s3.addAll(List.of("a", "b", "c"));
        System.out.println("组合 TreeSet：addAll 3 个元素后 added = " + s3.added() + "（换实现不用改计数逻辑）");
    }
}
