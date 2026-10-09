import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * 「已受理」之后的事：一个返回 202 的导出接口，任务由后台执行。
 * 后台的每一步由测试代码手动推进（runNext、crash、tick），所以每个场景的顺序是确定的。
 */
public class AcceptLab {
    enum State { QUEUED, RUNNING, SUCCEEDED, FAILED }
    static final Set<State> TERMINAL = Set.of(State.SUCCEEDED, State.FAILED);

    static final class Job {
        final String id; final boolean willFail;
        State state = State.QUEUED; int attempt; long leaseUntil; String error;
        Job(String id, boolean willFail) { this.id = id; this.willFail = willFail; }
    }

    /** 任务表。「持久化」的含义在这里是：进程重启后仍然拿得到同一个对象 */
    static final class Store {
        final Map<String, Job> jobs = new LinkedHashMap<>();
        final Map<String, String> byIdempotencyKey = new LinkedHashMap<>();
        final Deque<String> queue = new ArrayDeque<>();
        int seq;
    }

    static final class Service implements AutoCloseable {
        final Store store; final boolean idempotency, leases, guardedTransitions;
        final HttpServer http; long now;

        Service(Store store, boolean idempotency, boolean leases, boolean guardedTransitions) throws Exception {
            this.store = store; this.idempotency = idempotency; this.leases = leases; this.guardedTransitions = guardedTransitions;
            http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            http.setExecutor(Executors.newFixedThreadPool(4));
            http.createContext("/jobs", this::handle);
            http.start();
        }

        int port() { return http.getAddress().getPort(); }

        synchronized String submit(String key, boolean willFail) {
            if (idempotency && key != null && store.byIdempotencyKey.containsKey(key)) return store.byIdempotencyKey.get(key);
            String id = "job-" + (++store.seq);
            store.jobs.put(id, new Job(id, willFail));
            store.queue.add(id);
            if (key != null) store.byIdempotencyKey.put(key, id);
            return id;
        }

        void handle(HttpExchange ex) throws java.io.IOException {
            String path = ex.getRequestURI().getPath(), query = String.valueOf(ex.getRequestURI().getQuery());
            if (ex.getRequestMethod().equals("POST")) {
                String id = submit(ex.getRequestHeaders().getFirst("Idempotency-Key"), query.contains("fail=1"));
                if (query.contains("slow=1")) try { Thread.sleep(600); } catch (InterruptedException ignored) { }   // 任务已经登记，响应迟迟不回
                ex.getResponseHeaders().add("Location", "/jobs/" + id);
                respond(ex, 202, "{\"id\":\"" + id + "\",\"state\":\"QUEUED\"}");
            } else {
                Job j; synchronized (this) { j = store.jobs.get(path.substring(path.lastIndexOf('/') + 1)); }
                if (j == null) respond(ex, 404, "{\"error\":\"no such job\"}");
                else synchronized (this) { respond(ex, 200, "{\"id\":\"" + j.id + "\",\"state\":\"" + j.state + "\",\"attempt\":" + j.attempt + (j.error == null ? "" : ",\"error\":\"" + j.error + "\"") + "}"); }
            }
        }

        static void respond(HttpExchange ex, int code, String body) throws java.io.IOException {
            byte[] b = body.getBytes();
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        }

        /** 后台取出一个排队的任务开始执行，占用一段租约 */
        synchronized String start() {
            String id = store.queue.poll();
            if (id == null) return null;
            Job j = store.jobs.get(id);
            j.state = State.RUNNING; j.attempt++; j.leaseUntil = now + 10;
            return id;
        }

        /** 把任务置为终态；guardedTransitions 打开时只允许从 RUNNING 进入终态 */
        synchronized boolean finish(String id) {
            Job j = store.jobs.get(id);
            if (guardedTransitions && j.state != State.RUNNING) return false;
            if (j.willFail) { j.state = State.FAILED; j.error = "source table missing"; } else j.state = State.SUCCEEDED;
            return true;
        }

        /** 带执行序号的完成上报：序号不是当前这一次执行的，拒绝 */
        synchronized boolean finish(String id, int attempt) {
            Job j = store.jobs.get(id);
            if (j.state != State.RUNNING || j.attempt != attempt) return false;
            return finish(id);
        }

        /** 一条迟到的进度上报：把状态写成 RUNNING */
        synchronized boolean lateProgress(String id) {
            Job j = store.jobs.get(id);
            if (guardedTransitions && TERMINAL.contains(j.state)) return false;
            j.state = State.RUNNING;
            return true;
        }

        /** 时间前进；租约过期的 RUNNING 任务重新排队 */
        synchronized void tick(long delta) {
            now += delta;
            if (!leases) return;
            for (Job j : store.jobs.values()) if (j.state == State.RUNNING && j.leaseUntil < now) { j.state = State.QUEUED; store.queue.add(j.id); }
        }

        @Override public void close() { http.stop(0); }
    }

    static final HttpClient CLIENT = HttpClient.newHttpClient();

    static HttpResponse<String> post(Service s, String query, String key, long timeoutMs) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + s.port() + "/jobs" + query)).POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofMillis(timeoutMs));
        if (key != null) b.header("Idempotency-Key", key);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String get(Service s, String id) throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + s.port() + "/jobs/" + id)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }

    static String state(String response) { int i = response.indexOf("\"state\":\""); return i < 0 ? "?" : response.substring(i + 9, response.indexOf('"', i + 9)); }

    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    public static void main(String[] args) throws Exception {
        out("env", "java.version=" + System.getProperty("java.version"));

        // 一、202 只说明已登记
        try (Service s = new Service(new Store(), false, false, false)) {
            HttpResponse<String> r = post(s, "?fail=1", null, 5000);
            String id = "job-1";
            out("accepted.response", "POST 返回 " + r.statusCode() + "，Location: " + r.headers().firstValue("Location").orElse("-") + "，响应体 " + r.body());
            s.start(); s.finish(id);
            out("accepted.final", "后台执行完之后 GET：" + get(s, id));
        }

        // 二、提交时客户端超时，再提交一次
        try (Service s = new Service(new Store(), false, false, false)) {
            String first;
            try { first = "返回 " + post(s, "?slow=1", null, 200).statusCode(); } catch (HttpTimeoutException e) { first = "客户端超时"; }
            HttpResponse<String> second = post(s, "", null, 5000);
            out("retry.no_key", "第一次提交：" + first + "；重试返回 " + second.statusCode() + " " + second.body() + "；服务端登记的任务数 " + s.store.jobs.size());
        }
        try (Service s = new Service(new Store(), true, false, false)) {
            String first;
            try { first = "返回 " + post(s, "?slow=1", "export-2026-10-09-a", 200).statusCode(); } catch (HttpTimeoutException e) { first = "客户端超时"; }
            HttpResponse<String> second = post(s, "", "export-2026-10-09-a", 5000);
            out("retry.with_key", "带同一个 Idempotency-Key：第一次提交：" + first + "；重试返回 " + second.statusCode() + " " + second.body() + "；服务端登记的任务数 " + s.store.jobs.size());
        }

        // 三、轮询：把「不是 RUNNING」当成结束
        try (Service s = new Service(new Store(), false, false, false)) {
            post(s, "", null, 5000);
            String seen = state(get(s, "job-1"));
            boolean naiveDone = !seen.equals("RUNNING");
            boolean done = TERMINAL.stream().anyMatch(t -> t.name().equals(seen));
            out("poll.terminal_set", "任务还在排队时查询到 " + seen + "：「不是 RUNNING 就算结束」的判断 = " + naiveDone + "，「属于终态集合才算结束」的判断 = " + done);
        }

        // 四、进程重启
        Store memory = new Store();
        try (Service s = new Service(memory, false, false, false)) { post(s, "", null, 5000); s.start(); }
        try (Service s = new Service(new Store(), false, false, false)) {
            out("restart.in_memory", "任务表在内存里，重启后 GET job-1：" + get(s, "job-1"));
        }
        Store durable = new Store();
        try (Service s = new Service(durable, false, false, false)) { post(s, "", null, 5000); s.start(); }
        try (Service s = new Service(durable, false, false, false)) {
            s.tick(100);
            String a = get(s, "job-1");
            String next = s.start();
            out("restart.durable_no_lease", "任务表持久化、没有租约：重启并经过 100 个时间单位后 GET job-1：" + a + "；后台再取任务得到 " + next);
        }
        Store leased = new Store();
        try (Service s = new Service(leased, false, true, true)) { post(s, "", null, 5000); s.start(); }
        try (Service s = new Service(leased, false, true, true)) {
            s.tick(100);
            String a = get(s, "job-1");
            String next = s.start(); s.finish(next);
            out("restart.durable_lease", "任务表持久化、执行中的任务带租约：重启并经过 100 个时间单位后 GET job-1：" + a + "；后台再取任务得到 " + next + "，执行完后 " + get(s, "job-1"));
        }

        // 五、状态只能向前
        try (Service s = new Service(new Store(), false, false, false)) {
            post(s, "", null, 5000); s.start(); s.finish("job-1");
            boolean applied = s.lateProgress("job-1");
            out("transition.unguarded", "任务成功之后又到了一条迟到的进度上报：写入 = " + applied + "，GET：" + get(s, "job-1"));
        }
        try (Service s = new Service(new Store(), false, false, true)) {
            post(s, "", null, 5000); s.start(); s.finish("job-1");
            boolean applied = s.lateProgress("job-1");
            boolean again = s.finish("job-1");
            out("transition.guarded", "终态之后拒绝回退：迟到的进度上报写入 = " + applied + "，重复的完成上报写入 = " + again + "，GET：" + get(s, "job-1"));
        }
        // 六、旧的执行者在租约过期之后才上报完成
        Store st = new Store();
        try (Service s = new Service(st, false, true, true)) {
            post(s, "", null, 5000);
            String id = s.start();
            s.tick(100);                       // 第一个执行者卡住，租约过期，任务重新排队
            String id2 = s.start();            // 第二个执行者接手
            boolean stale = s.finish(id);      // 第一个执行者醒来，上报完成
            out("stale_worker", "租约过期后任务被第二个执行者接手（attempt " + st.jobs.get(id2).attempt + "），旧执行者此时上报完成：写入 = " + stale + "，GET：" + get(s, id));
        }
        Store st2 = new Store();
        try (Service s = new Service(st2, false, true, true)) {
            post(s, "", null, 5000);
            String id = s.start(); int firstAttempt = st2.jobs.get(id).attempt;
            s.tick(100);
            s.start();
            boolean stale = s.finish(id, firstAttempt);
            out("stale_worker.fenced", "完成上报带上执行序号：旧执行者（attempt " + firstAttempt + "）上报写入 = " + stale + "，GET：" + get(s, id)
                    + "；当前执行者（attempt 2）上报写入 = " + s.finish(id, 2) + "，GET：" + get(s, id));
        }
        System.exit(0);
    }
}
