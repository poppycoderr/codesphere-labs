import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

public class Fn {
    // 1. record 只是浅不可变
    record Cart(String user, List<String> items) {}
    record SafeCart(String user, List<String> items) {
        SafeCart { items = List.copyOf(items); }
    }

    static int expensiveCalls = 0;
    static String expensiveDefault() { expensiveCalls++; return "default"; }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1 record 浅不可变");
        List<String> src = new ArrayList<>(List.of("book"));
        Cart c = new Cart("u1", src);
        src.add("phone");
        System.out.println("Cart.items after caller mutates source: " + c.items());
        c.items().add("pen");
        System.out.println("Cart.items after mutate via accessor: " + c.items());
        List<String> src2 = new ArrayList<>(List.of("book"));
        SafeCart s = new SafeCart("u1", src2);
        src2.add("phone");
        System.out.println("SafeCart.items after caller mutates source: " + s.items());
        try { s.items().add("pen"); } catch (UnsupportedOperationException e) { System.out.println("SafeCart add -> " + e.getClass().getSimpleName()); }
        System.out.println("record equals: " + new SafeCart("u", List.of("a")).equals(new SafeCart("u", List.of("a"))));

        System.out.println("== 2 组合顺序");
        UnaryOperator<BigDecimal> off20 = p -> p.multiply(new BigDecimal("0.8"));
        UnaryOperator<BigDecimal> minus30 = p -> p.subtract(new BigDecimal("30"));
        Function<BigDecimal, BigDecimal> a = off20.andThen(minus30);
        Function<BigDecimal, BigDecimal> b = minus30.andThen(off20);
        BigDecimal price = new BigDecimal("200");
        System.out.println("8折再减30: " + a.apply(price) + "  减30再8折: " + b.apply(price));
        List<UnaryOperator<BigDecimal>> rules = List.of(off20, minus30);
        Function<BigDecimal, BigDecimal> all = rules.stream().map(r -> (Function<BigDecimal, BigDecimal>) r).reduce(Function.identity(), Function::andThen);
        System.out.println("reduce 组合: " + all.apply(price));

        System.out.println("== 3 peek 与 count");
        AtomicInteger peeked = new AtomicInteger();
        long n = List.of(1, 2, 3, 4, 5).stream().peek(x -> peeked.incrementAndGet()).count();
        System.out.println("count=" + n + " peek 执行次数=" + peeked.get());
        peeked.set(0);
        long n2 = List.of(1, 2, 3, 4, 5).stream().filter(x -> x > 0).peek(x -> peeked.incrementAndGet()).count();
        System.out.println("加 filter 后 count=" + n2 + " peek 执行次数=" + peeked.get());
        peeked.set(0);
        List.of(1, 2, 3, 4, 5).stream().map(x -> { peeked.incrementAndGet(); return x; }).count();
        System.out.println("map 中的副作用执行次数=" + peeked.get());

        System.out.println("== 4 并行流共享可变状态");
        int lost = 0, exceptions = 0;
        for (int round = 0; round < 20; round++) {
            List<Integer> out = new ArrayList<>();
            try {
                IntStream.range(0, 100_000).parallel().forEach(out::add);
                if (out.size() != 100_000) lost++;
            } catch (ArrayIndexOutOfBoundsException e) { exceptions++; }
        }
        System.out.println("20 轮 forEach(ArrayList::add)：丢元素 " + lost + " 轮，抛异常 " + exceptions + " 轮");
        int bad = 0;
        for (int round = 0; round < 20; round++) {
            List<Integer> out = IntStream.range(0, 100_000).parallel().boxed().toList();
            if (out.size() != 100_000) bad++;
        }
        System.out.println("20 轮 toList()：不一致 " + bad + " 轮");

        System.out.println("== 5 Optional");
        try { Optional.of(null); } catch (NullPointerException e) { System.out.println("Optional.of(null) -> NPE"); }
        Optional<String> present = Optional.of("v");
        present.orElse(expensiveDefault());
        System.out.println("值存在时 orElse 仍调用默认值方法 " + expensiveCalls + " 次");
        expensiveCalls = 0;
        present.orElseGet(Fn::expensiveDefault);
        System.out.println("值存在时 orElseGet 调用 " + expensiveCalls + " 次");
        Optional<String> nullOpt = null;
        try { nullOpt.isPresent(); } catch (NullPointerException e) { System.out.println("Optional 变量本身为 null -> NPE"); }

        System.out.println("== 6 记忆化与 computeIfAbsent 递归");
        Map<Integer, Long> memo = new ConcurrentHashMap<>();
        try { System.out.println("fib(30)=" + fib(30, memo)); }
        catch (IllegalStateException e) { System.out.println("ConcurrentHashMap 递归 computeIfAbsent -> " + e.getMessage()); }
        Map<Integer, Long> hm = new HashMap<>();
        try { System.out.println("fib(30)=" + fib(30, hm)); }
        catch (ConcurrentModificationException e) { System.out.println("HashMap 递归 computeIfAbsent -> ConcurrentModificationException"); }
    }

    static long fib(int k, Map<Integer, Long> memo) {
        if (k < 2) return k;
        return memo.computeIfAbsent(k, i -> fib(i - 1, memo) + fib(i - 2, memo));
    }
}
