package labs.discovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import labs.discovery.consumer.ConsumerApp;
import labs.discovery.provider.ProviderApp;
import labs.discovery.registry.RegistryApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * 实例下线后，调用方多久不再访问它：分别记录实例从注册表、注册中心的响应缓存、调用方本地副本、负载均衡器的实例列表中消失的时刻，
 * 以及这期间打到这个实例的请求。所有间隔都缩短到秒级：心跳 1 秒、租约 3 秒、剔除检查 1 秒、响应缓存 3 秒、客户端拉取 3 秒、负载均衡缓存 5 秒。
 */
class DeregistrationTest {

    static ConfigurableApplicationContext registry;
    static int registryPort;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startRegistry() {
        registry = new SpringApplicationBuilder(RegistryApp.class).properties(
                "server.port=0",
                "spring.application.name=registry",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false",
                "eureka.server.enable-self-preservation=false",
                "eureka.server.eviction-interval-timer-in-ms=1000",
                "eureka.server.response-cache-update-interval-ms=3000",
                "eureka.server.use-read-only-response-cache=true",
                "eureka.server.wait-time-in-ms-when-sync-empty=0",
                "logging.level.root=WARN").run();
        registryPort = ((WebServerApplicationContext) registry).getWebServer().getPort();
    }

    @AfterAll
    static void stopRegistry() {
        registry.close();
    }

    static String zone() {
        return "http://localhost:" + registryPort + "/eureka/";
    }

    static ConfigurableApplicationContext provider(String id, boolean unregisterOnShutdown) {
        return new SpringApplicationBuilder(ProviderApp.class).properties(
                "server.port=0",
                "spring.application.name=provider",
                "eureka.client.service-url.defaultZone=" + zone(),
                "eureka.client.fetch-registry=false",
                "eureka.client.jersey.enabled=false",
                "eureka.client.should-unregister-on-shutdown=" + unregisterOnShutdown,
                "eureka.client.initial-instance-info-replication-interval-seconds=1",
                "eureka.client.instance-info-replication-interval-seconds=1",
                "eureka.instance.instance-id=" + id,
                "eureka.instance.hostname=localhost",
                "eureka.instance.lease-renewal-interval-in-seconds=1",
                "eureka.instance.lease-expiration-duration-in-seconds=3",
                "logging.level.root=WARN").run();
    }

    static ConfigurableApplicationContext consumer() {
        return new SpringApplicationBuilder(ConsumerApp.class).properties(
                "server.port=0",
                "spring.application.name=consumer",
                "eureka.client.service-url.defaultZone=" + zone(),
                "eureka.client.register-with-eureka=false",
                "eureka.client.jersey.enabled=false",
                "eureka.client.registry-fetch-interval-seconds=3",
                "spring.cloud.loadbalancer.cache.ttl=5s",
                "spring.cloud.loadbalancer.retry.enabled=false",
                "logging.level.root=WARN").run();
    }

    // 「可见」指实例在这一层存在且状态为 UP：调用方默认只使用 UP 的实例
    static boolean inRegistry(String id) {
        Application app = registry.getBean(PeerAwareInstanceRegistry.class).getApplication("PROVIDER");
        return app != null && app.getByInstanceId(id) != null && app.getByInstanceId(id).getStatus() == InstanceStatus.UP;
    }

    static boolean inServerApi(String id) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(zone() + "apps/PROVIDER")).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        int i = r.body().indexOf("\"instanceId\":\"" + id + "\"");
        if (i < 0) {
            return false;
        }
        String rest = r.body().substring(i);
        return rest.indexOf("\"status\":\"UP\"") >= 0 && rest.indexOf("\"status\":\"UP\"") < rest.indexOf("\"overriddenStatus\"");
    }

    static boolean inClient(ConfigurableApplicationContext consumer, String id) {
        Application app = consumer.getBean(EurekaClient.class).getApplication("PROVIDER");
        return app != null && app.getByInstanceId(id) != null && app.getByInstanceId(id).getStatus() == InstanceStatus.UP;
    }

    /** 一次下线的观测结果：各层消失的秒数、打到这个实例的请求数与失败数。 */
    record Run(double registry, double serverApi, double client, double lastHit, int hits, int errors) {
    }

    /**
     * 启动 A、B 与调用方，确认流量落在两个实例上后执行 shutdown，持续调用并记录各层状态直到 B 从所有层消失。
     */
    Run observe(boolean unregisterOnShutdown, Shutdown how) throws Exception {
        ConfigurableApplicationContext a = provider("A", true);
        ConfigurableApplicationContext b = provider("B", unregisterOnShutdown);
        ConfigurableApplicationContext consumer = consumer();
        RestClient client = consumer.getBean("lbRestClient", RestClient.Builder.class).build();
        try {
            Map<String, Integer> seen = new ConcurrentHashMap<>();
            long deadline = System.currentTimeMillis() + 60_000;
            while ((seen.getOrDefault("A", 0) == 0 || seen.getOrDefault("B", 0) == 0) && System.currentTimeMillis() < deadline) {
                try {
                    seen.merge(client.get().uri("http://provider/who").retrieve().body(String.class), 1, Integer::sum);
                } catch (Exception ignored) {
                    // 还在启动
                }
                Thread.sleep(100);
            }
            assertTrue(seen.getOrDefault("B", 0) > 0, "流量没有落到 B");

            List<double[]> calls = new CopyOnWriteArrayList<>();     // [时刻, 0=A 1=B 2=失败]
            AtomicBoolean running = new AtomicBoolean(true);
            long t0 = System.nanoTime();
            Thread traffic = Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    double t = (System.nanoTime() - t0) / 1e9;
                    try {
                        String who = client.get().uri("http://provider/who").retrieve().body(String.class);
                        calls.add(new double[] {t, "A".equals(who) ? 0 : 1});
                    } catch (Exception e) {
                        calls.add(new double[] {t, 2});
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            });
            Thread action = Thread.ofPlatform().start(() -> {
                try {
                    how.apply(b);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            double reg = -1;
            double api = -1;
            double cli = -1;
            while ((reg < 0 || api < 0 || cli < 0) && (System.nanoTime() - t0) / 1e9 < 40) {
                double t = (System.nanoTime() - t0) / 1e9;
                if (reg < 0 && !inRegistry("B")) {
                    reg = t;
                }
                if (api < 0 && !inServerApi("B")) {
                    api = t;
                }
                if (cli < 0 && !inClient(consumer, "B")) {
                    cli = t;
                }
                Thread.sleep(100);
            }
            action.join();
            Thread.sleep(8000);                                      // 让负载均衡缓存也过期
            running.set(false);
            traffic.join();
            if (b.isActive()) {
                b.close();
            }
            double lastHit = calls.stream().filter(c -> c[1] != 0).mapToDouble(c -> c[0]).max().orElse(0);
            int hits = (int) calls.stream().filter(c -> c[1] == 1).count();
            int errors = (int) calls.stream().filter(c -> c[1] == 2).count();
            return new Run(r1(reg), r1(api), r1(cli), r1(lastHit), hits, errors);
        } finally {
            consumer.close();
            a.close();
            if (b.isActive()) {
                b.close();
            }
            Thread.sleep(5000);                                      // 让注册表清空，下一个场景从干净的状态开始
        }
    }

    interface Shutdown {
        void apply(ConfigurableApplicationContext b) throws Exception;
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static String describe(Run r) {
        return "注册表 " + r.registry() + "s、注册中心接口 " + r.serverApi() + "s、调用方本地副本 " + r.client() + "s 后不再有 B；最后一次请求落到 B 是 "
                + r.lastHit() + "s；此后 B 收到 " + r.hits() + " 个请求、失败 " + r.errors() + " 个";
    }

    @Test
    void layersOfVisibility() throws Exception {
        Run graceful = observe(true, ConfigurableApplicationContext::close);
        // 模拟进程崩溃：不走 Spring 的生命周期（它会先把状态改为 DOWN），直接停掉端口与心跳
        Run abrupt = observe(false, b -> {
            ((WebServerApplicationContext) b).getWebServer().stop();
            b.getBean(EurekaClient.class).shutdown();
        });
        Run ordered = observe(true, b -> {
            b.getBean(ServiceRegistry.class).deregister(b.getBean(Registration.class));
            Thread.sleep(16_000);                                    // 响应缓存 3s + 拉取 3s + 负载均衡缓存 5s，再留余量
            b.close();
        });
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "timings.txt"),
                "graceful=" + graceful + "\nabrupt=" + abrupt + "\nordered=" + ordered + "\n");

        Facts.record("graceful.order", "正常停机：注册中心（注册表与接口）先变，调用方本地副本其次，负载均衡器最后才停止选择 B="
                + (Math.max(graceful.registry(), graceful.serverApi()) <= graceful.client() && graceful.client() <= graceful.lastHit()));
        Facts.record("abrupt.order", "崩溃：同样按注册中心、本地副本、负载均衡器的顺序传播="
                + (Math.max(abrupt.registry(), abrupt.serverApi()) <= abrupt.client() && abrupt.client() <= abrupt.lastHit()));
        Facts.record("graceful.errors", "正常停机直接关闭：B 的端口已关闭、调用方仍在选择它，期间出现失败的请求=" + (graceful.errors() > 0));
        Facts.record("abrupt.later", "崩溃（不注销、靠心跳过期）：B 从注册表消失比正常停机晚 2 秒以上=" + (abrupt.registry() - graceful.registry() > 2));
        Facts.record("abrupt.errors", "崩溃：失败的请求比正常停机多=" + (abrupt.errors() > graceful.errors()));
        Facts.record("ordered.errors", "先注销、等待 16 秒、再停服务：失败的请求 " + ordered.errors() + " 个");
        Facts.record("ordered.hits", "先注销之后，B 仍然收到过请求（还在服务，所以没有失败）=" + (ordered.hits() > 0));
    }
}
