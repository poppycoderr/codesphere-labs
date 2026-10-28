import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * 响应式流的信号契约：终态之后不再发、取消向上游传播、request 是累计额度、预取与内部队列、flatMap 的顺序与并发、组装期与订阅期。
 * 时间相关的场景用虚拟时间，输出是确定的。
 */
public class Lab {

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    /** 不守规矩的发布者：完成之后还发元素、还发错误。 */
    static Publisher<Integer> rogue() {
        return s -> {
            s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                }

                @Override
                public void cancel() {
                }
            });
            s.onNext(1);
            s.onNext(2);
            s.onComplete();
            s.onNext(3);
            s.onError(new IllegalStateException("late"));
        };
    }

    public static void main(String[] args) throws Exception {
        // 一、终态之后不能再发
        List<Object> dropped = new CopyOnWriteArrayList<>();
        Hooks.onNextDropped(dropped::add);
        Hooks.onErrorDropped(e -> dropped.add(e.getMessage()));
        List<String> signals = new ArrayList<>();
        Flux.from(rogue()).map(i -> i * 10).subscribe(v -> signals.add("next " + v), e -> signals.add("error"), () -> signals.add("complete"));
        out("terminal.rogue", "发布者在 onComplete 之后又发了 3 和一个错误：订阅者收到 " + signals + "；被丢弃并交给钩子的 " + dropped);
        Hooks.resetOnNextDropped();
        Hooks.resetOnErrorDropped();

        List<String> sinkSeen = new ArrayList<>();
        Flux.<Integer>create(sink -> {
            sink.next(1);
            sink.complete();
            sink.next(2);
            sinkSeen.add("complete 之后 isCancelled=" + sink.isCancelled());
        }).subscribe(v -> sinkSeen.add("next " + v), e -> sinkSeen.add("error"), () -> sinkSeen.add("complete"));
        out("terminal.create", "Flux.create 里 complete 之后再 next(2)：订阅者收到 " + sinkSeen);

        // 二、取消向上游传播
        AtomicInteger generated = new AtomicInteger();
        List<String> upstreamEvents = new ArrayList<>();
        List<Integer> taken = Flux.<Integer, Integer>generate(() -> 0, (st, sink) -> {
            generated.incrementAndGet();
            sink.next(st);
            return st + 1;
        }).doOnCancel(() -> upstreamEvents.add("cancel")).doOnComplete(() -> upstreamEvents.add("complete")).take(3).collectList().block();
        out("cancel.take", "无限的 generate 后接 take(3)：得到 " + taken + "，上游一共生成 " + generated.get() + " 个，上游看到 " + upstreamEvents);

        AtomicInteger mapped = new AtomicInteger();
        List<String> errEvents = new ArrayList<>();
        StepVerifier.create(Flux.range(1, 10).doOnCancel(() -> errEvents.add("cancel")).map(i -> {
            mapped.incrementAndGet();
            if (i == 3) {
                throw new IllegalArgumentException("bad " + i);
            }
            return i;
        })).expectNext(1, 2).expectErrorMessage("bad 3").verify();
        out("error.terminal", "range(1, 10) 的 map 在第 3 个元素抛异常：下游收到 1、2 和错误，map 执行了 " + mapped.get() + " 次，上游看到 " + errEvents + "（剩下 7 个不再处理）");

        // 三、request(n) 是累计额度，不是确认
        List<Long> requests = new ArrayList<>();
        List<Integer> got = new ArrayList<>();
        BaseSubscriber<Integer> sub = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription s) {
                s.request(2);
                s.request(3);
            }

            @Override
            protected void hookOnNext(Integer v) {
                got.add(v);
            }
        };
        Flux.range(1, 100).doOnRequest(requests::add).subscribe(sub);
        out("request.additive", "订阅时连续 request(2)、request(3)，之后不再请求：上游收到请求 " + requests + "，订阅者收到 " + got.size() + " 个后停住");
        sub.dispose();

        // 四、预取：下游 request(1) 不等于全链只有一个元素在途
        List<Long> upReq = new CopyOnWriteArrayList<>();
        AtomicInteger emitted = new AtomicInteger();
        BaseSubscriber<Integer> one = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            protected void hookOnNext(Integer v) {
            }
        };
        Flux.range(1, 10_000).doOnRequest(upReq::add).doOnNext(i -> emitted.incrementAndGet()).publishOn(Schedulers.single()).subscribe(one);
        Thread.sleep(300);
        out("prefetch.publishOn", "publishOn 之后下游只 request(1)：publishOn 向上游请求 " + upReq + "，上游已经发出 " + emitted.get() + " 个（在 publishOn 的队列里等着）");
        one.dispose();

        List<Long> limited = new CopyOnWriteArrayList<>();
        Flux.range(1, 40).doOnRequest(limited::add).limitRate(10).blockLast();
        out("prefetch.limitRate", "limitRate(10)，下游无界请求：上游收到的请求依次为 " + limited);

        // 五、flatMap 不保证顺序，并发参数限制的是活跃的内层订阅
        out("order.flatMap", "元素 1、2、3 各自延迟 3、2、1 秒：flatMap 输出 " + order(false));
        out("order.concatMap", "同样的输入：concatMap 输出 " + order(true));
        concurrency(6, 2, false);
        concurrency(6, 6, false);
        concurrency(6, 1, true);

        // 六、组装期与订阅期
        AtomicInteger calls = new AtomicInteger();
        Mono<Integer> eager = Mono.just(expensive(calls));
        int afterAssembly = calls.get();
        eager.block();
        eager.block();
        out("lazy.just", "Mono.just(f())：还没订阅时 f 已执行 " + afterAssembly + " 次，订阅两次后共 " + calls.get() + " 次（两次订阅拿到同一个值）");
        AtomicInteger calls2 = new AtomicInteger();
        Mono<Integer> lazy = Mono.defer(() -> Mono.just(expensive(calls2)));
        int before = calls2.get();
        lazy.block();
        lazy.block();
        out("lazy.defer", "Mono.defer(() -> Mono.just(f()))：订阅前 " + before + " 次，订阅两次后 " + calls2.get() + " 次");
        AtomicInteger side = new AtomicInteger();
        Flux<Integer> notSubscribed = Flux.range(1, 5).doOnNext(i -> side.incrementAndGet());
        out("lazy.subscribe", "组装了 doOnNext 却没有订阅：副作用执行 " + side.get() + " 次（" + notSubscribed.getClass().getSimpleName() + "）");
    }

    static int expensive(AtomicInteger calls) {
        return calls.incrementAndGet();
    }

    static List<Integer> order(boolean concat) {
        List<Integer> seen = new ArrayList<>();
        StepVerifier.withVirtualTime(() -> {
            Flux<Integer> src = Flux.just(1, 2, 3);
            return concat ? src.concatMap(i -> Mono.delay(Duration.ofSeconds(4 - i)).thenReturn(i)) : src.flatMap(i -> Mono.delay(Duration.ofSeconds(4 - i)).thenReturn(i));
        }).thenAwait(Duration.ofSeconds(10)).recordWith(() -> seen).thenConsumeWhile(x -> true).expectComplete().verify(Duration.ofSeconds(10));
        return seen;
    }

    static void concurrency(int n, int limit, boolean concat) {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        AtomicInteger activeF = new AtomicInteger();
        AtomicInteger maxF = new AtomicInteger();
        AtomicLong start = new AtomicLong();
        AtomicLong end = new AtomicLong();
        StepVerifier.withVirtualTime(() -> {
            VirtualTimeScheduler vts = VirtualTimeScheduler.get();
            Flux<Integer> src = Flux.range(1, n);
            Function<Integer, Mono<Integer>> inner = i -> Mono.delay(Duration.ofSeconds(1)).thenReturn(i)
                    .doOnSubscribe(s -> {
                        max.accumulateAndGet(active.incrementAndGet(), Math::max);
                        maxF.accumulateAndGet(activeF.incrementAndGet(), Math::max);
                    })
                    .doOnTerminate(active::decrementAndGet)
                    .doFinally(t -> activeF.decrementAndGet());
            Flux<Integer> f = concat ? src.concatMap(inner) : src.flatMap(inner, limit);
            return f.doOnSubscribe(s -> start.set(vts.now(TimeUnit.MILLISECONDS))).doOnComplete(() -> end.set(vts.now(TimeUnit.MILLISECONDS)));
        }).thenAwait(Duration.ofSeconds(20)).expectNextCount(n).expectComplete().verify(Duration.ofSeconds(10));
        out(concat ? "concurrency.concatMap" : "concurrency.flatMap", n + " 个各需 1 秒的内层，" + (concat ? "concatMap" : "flatMap(f, " + limit + ")") + "：同时活跃的内层最多 " + max.get()
                + " 个（用 doFinally 减计数时读到 " + maxF.get() + " 个），虚拟时间共 " + (end.get() - start.get()) / 1000 + " 秒");
    }
}
