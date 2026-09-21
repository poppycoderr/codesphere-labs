import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

public class StreamSlice {
    public static void main(String[] a) {
        AtomicInteger filtered = new AtomicInteger(), mapped = new AtomicInteger();
        Stream<Integer> pipeline = IntStream.rangeClosed(1, 1_000_000).boxed()
                .filter(i -> { filtered.incrementAndGet(); return i % 7 == 0; })
                .map(i -> { mapped.incrementAndGet(); return i * 10; });
        System.out.println("只组装流水线、还没调用终止操作：filter 执行 " + filtered + " 次，map 执行 " + mapped + " 次");
        Optional<Integer> first = pipeline.findFirst();
        System.out.println("findFirst() 之后：结果 " + first.get() + "，filter 执行 " + filtered + " 次，map 执行 " + mapped + " 次");

        System.out.println("\n逐元素流过（不是先全部 filter 再全部 map）：");
        Stream.of("a", "bb", "ccc").peek(s -> System.out.println("  filter 看到 " + s)).filter(s -> s.length() > 1)
              .peek(s -> System.out.println("  map 看到    " + s)).map(String::toUpperCase).forEach(s -> System.out.println("  终止操作收到 " + s));

        System.out.println("\nsorted() 是有状态操作，会先收集全部元素：");
        Stream.of("c", "a", "b").peek(s -> System.out.println("  sorted 之前 " + s)).sorted().forEach(s -> System.out.println("  sorted 之后 " + s));
    }
}
