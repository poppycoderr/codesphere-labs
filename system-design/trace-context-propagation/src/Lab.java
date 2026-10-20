import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

/** 追踪上下文在哪一跳丢失：线程池、CompletableFuture、虚拟线程、手写 HTTP 调用；传播与采样、导出是不同的路径 */
public class Lab {
    static final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    /** 在父 span 里执行一段代码，返回「子 span 是否与父 span 同一条 trace，且以父 span 为 parent」 */
    static String child(Tracer tracer, String name, java.util.function.Consumer<Runnable> how) throws Exception {
        exporter.reset();
        Span parent = tracer.spanBuilder("parent").startSpan();
        CountDownLatch done = new CountDownLatch(1);
        try (Scope ignored = parent.makeCurrent()) {
            how.accept(() -> { tracer.spanBuilder(name).startSpan().end(); done.countDown(); });
        }
        done.await(5, TimeUnit.SECONDS);
        parent.end();
        SpanData p = find("parent"), c = find(name);
        boolean sameTrace = p.getTraceId().equals(c.getTraceId());
        return sameTrace && c.getParentSpanId().equals(p.getSpanId()) ? "同一条 trace，parent 正确" : "新的 trace（子 span 成了根）";
    }
    static SpanData find(String name) { return exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow(); }

    public static void main(String[] args) throws Exception {
        SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        Tracer tracer = provider.get("lab");
        out("env", "opentelemetry-sdk=1.67.0 java=" + Runtime.version());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        out("same_thread", "同一线程里创建子 span：" + child(tracer, "c0", Runnable::run));
        out("pool.plain", "提交到普通线程池：" + child(tracer, "c1", pool::submit));
        ExecutorService wrapped = Context.taskWrapping(pool);
        out("pool.wrapped", "提交到 Context.taskWrapping 包装的线程池：" + child(tracer, "c2", wrapped::submit));
        out("cf.async", "CompletableFuture.runAsync（公共池）：" + child(tracer, "c3", CompletableFuture::runAsync));
        out("cf.wrapped", "CompletableFuture.runAsync 传入包装后的线程池：" + child(tracer, "c4", r -> CompletableFuture.runAsync(r, wrapped)));
        out("virtual", "Thread.ofVirtual().start：" + child(tracer, "c5", r -> Thread.ofVirtual().start(r)));
        out("context.wrap", "普通线程池，但提交前用 Context.current().wrap(task)：" + child(tracer, "c6", r -> pool.submit(Context.current().wrap(r))));

        // HTTP：服务端从请求头提取上下文，再创建 server span
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        TextMapGetter<com.sun.net.httpserver.Headers> getter = new TextMapGetter<>() {
            public Iterable<String> keys(com.sun.net.httpserver.Headers h) { return h.keySet(); }
            public String get(com.sun.net.httpserver.Headers h, String k) { return h == null ? null : h.getFirst(k); }
        };
        List<String> seenHeaders = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/", ex -> {
            seenHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("traceparent")));
            Context extracted = W3C.extract(Context.root(), ex.getRequestHeaders(), getter);
            tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).setParent(extracted).startSpan().end();
            ex.sendResponseHeaders(204, -1); ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        HttpClient http = HttpClient.newHttpClient();
        for (boolean inject : new boolean[]{false, true}) {
            exporter.reset(); seenHeaders.clear();
            Span client = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            try (Scope ignored = client.makeCurrent()) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
                if (inject) W3C.inject(Context.current(), b, HttpRequest.Builder::header);
                http.send(b.build(), HttpResponse.BodyHandlers.discarding());
            }
            client.end();
            SpanData c = find("client"), s = find("server");
            out(inject ? "http.inject" : "http.plain", (inject ? "注入 traceparent 后" : "手写 HttpClient 调用，不注入请求头") + "：服务端收到 traceparent=" + seenHeaders.get(0).replaceAll("[0-9a-f]{32}", "<traceId>").replaceAll("-[0-9a-f]{16}-", "-<spanId>-")
                    + "；服务端 span " + (c.getTraceId().equals(s.getTraceId()) ? "与客户端同一条 trace" : "是另一条 trace 的根"));
        }

        // 采样：根 span 不采样时，上下文照样传播，但标志位告诉下游不要记录
        SdkTracerProvider off = SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOff())).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        Tracer offTracer = off.get("lab");
        exporter.reset(); seenHeaders.clear();
        Span root = offTracer.spanBuilder("unsampled-root").startSpan();
        try (Scope ignored = root.makeCurrent()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
            W3C.inject(Context.current(), b, HttpRequest.Builder::header);
            http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        }
        root.end();
        out("sampling", "根 span 采样器为 parentBased(alwaysOff)：服务端收到 traceparent 末尾标志=" + seenHeaders.get(0).substring(seenHeaders.get(0).length() - 2)
                + "（采样位为 0，随机 trace-id 位为 1），客户端导出 " + exporter.getFinishedSpanItems().stream().filter(x -> !x.getName().equals("server")).count()
                + " 个 span；服务端用默认的 parentBased(alwaysOn) 采样器，跟随父级不采样，导出 " + exporter.getFinishedSpanItems().stream().filter(x -> x.getName().equals("server")).count() + " 个");

        // 导出：span 结束后才交给处理器；没有结束的 span 不会被导出
        exporter.reset();
        tracer.spanBuilder("never-ended").startSpan();
        out("export.unended", "开始但没有调用 end() 的 span，导出数=" + exporter.getFinishedSpanItems().size());
        pool.shutdown(); server.stop(0); provider.close(); off.close();
        System.exit(0);
    }
}
