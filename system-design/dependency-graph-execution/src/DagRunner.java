import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * 活动报名的依赖任务图：发布前校验、稳定顺序、并发上限下的调度、失败传播、重试与幂等。
 * 输出为「键<TAB>事实」。
 */
public class DagRunner {

    record Node(String id, long millis, List<String> deps) {
    }

    enum State { SUCCEEDED, FAILED, SKIPPED, CANCELLED }

    enum Policy { SKIP_DEPENDENTS, FAIL_FAST }

    // ---------- 发布前校验 ----------

    /** 缺失、重复、环分别报告；环只报一条真实路径，其余剩余节点标为「受阻塞」。 */
    static List<String> validate(List<Node> nodes) {
        List<String> errors = new ArrayList<>();
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node n : nodes) {
            if (byId.putIfAbsent(n.id(), n) != null) errors.add("重复节点: " + n.id());
        }
        for (Node n : byId.values()) {
            for (String d : n.deps()) {
                if (!byId.containsKey(d)) errors.add("缺失节点: " + n.id() + " 依赖的 " + d + " 不存在");
            }
        }
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Node n : byId.values()) {
            inDegree.putIfAbsent(n.id(), 0);
            for (String d : n.deps()) {
                if (!byId.containsKey(d)) continue;
                inDegree.merge(n.id(), 1, Integer::sum);
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(n.id());
            }
        }
        Deque<String> ready = inDegree.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayDeque::new));
        int done = 0;
        while (!ready.isEmpty()) {
            String cur = ready.poll();
            done++;
            for (String n : dependents.getOrDefault(cur, List.of())) {
                if (inDegree.merge(n, -1, Integer::sum) == 0) ready.offer(n);
            }
        }
        if (done < inDegree.size()) {
            Set<String> blocked = inDegree.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(TreeSet::new));
            List<String> path = new ArrayList<>();
            Map<String, Integer> seenAt = new HashMap<>();
            String cur = blocked.iterator().next();
            while (!seenAt.containsKey(cur)) {
                seenAt.put(cur, path.size());
                path.add(cur);
                cur = byId.get(cur).deps().stream().filter(blocked::contains).sorted().findFirst().orElseThrow();
            }
            List<String> cycle = new ArrayList<>(path.subList(seenAt.get(cur), path.size()));
            cycle.add(cur);
            errors.add("循环依赖: " + String.join(" -> ", cycle));
            errors.add("受环阻塞: " + blocked.stream().filter(n -> !cycle.contains(n)).toList());
        }
        return errors;
    }

    // ---------- 排序 ----------

    static List<String> order(List<Node> nodes, boolean sortedReady) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Node n : nodes) {
            inDegree.putIfAbsent(n.id(), 0);
            for (String d : n.deps()) {
                inDegree.merge(n.id(), 1, Integer::sum);
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(n.id());
            }
        }
        Queue<String> ready = sortedReady ? new PriorityQueue<>() : new ArrayDeque<>();
        inDegree.forEach((k, v) -> { if (v == 0) ready.add(k); });
        List<String> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            String cur = ready.poll();
            out.add(cur);
            for (String n : dependents.getOrDefault(cur, List.of())) {
                if (inDegree.merge(n, -1, Integer::sum) == 0) ready.add(n);
            }
        }
        return out;
    }

    // ---------- 执行 ----------

    record Result(Map<String, State> states, Map<String, String> reasons, long makespanMs, int maxRunning,
                  List<String> timeline) {
    }

    /** 固定并发上限执行 DAG：节点完成后解锁后继；失败时按策略跳过后继或取消全部。 */
    static Result run(List<Node> nodes, int limit, Policy policy, Function<Node, Callable<Void>> work) throws InterruptedException {
        Map<String, Node> byId = nodes.stream().collect(Collectors.toMap(Node::id, n -> n, (a, b) -> a, LinkedHashMap::new));
        Map<String, Integer> pending = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Node n : nodes) {
            pending.put(n.id(), n.deps().size());
            for (String d : n.deps()) dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(n.id());
        }
        Map<String, State> states = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        TreeSet<String> ready = pending.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        long t0 = System.nanoTime();
        LongSupplier now = () -> (System.nanoTime() - t0) / 1_000_000;

        ExecutorService pool = Executors.newFixedThreadPool(limit);
        CompletionService<String> done = new ExecutorCompletionService<>(pool);
        Map<String, Future<String>> inFlight = new HashMap<>();
        boolean aborted = false;
        while (!ready.isEmpty() || !inFlight.isEmpty()) {
            while (!aborted && !ready.isEmpty() && inFlight.size() < limit) {
                String id = ready.pollFirst();
                Callable<Void> task = work.apply(byId.get(id));
                inFlight.put(id, done.submit(() -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    timeline.add(now.getAsLong() + "ms start " + id);
                    try {
                        task.call();
                        return id;
                    } finally {
                        running.decrementAndGet();
                    }
                }));
            }
            if (inFlight.isEmpty()) break;
            Future<String> f = done.take();
            String id = inFlight.entrySet().stream().filter(e -> e.getValue() == f).findFirst().orElseThrow().getKey();
            inFlight.remove(id);
            try {
                f.get();
                states.put(id, State.SUCCEEDED);
                timeline.add(now.getAsLong() + "ms done  " + id);
                for (String n : dependents.getOrDefault(id, List.of())) {
                    if (!states.containsKey(n) && pending.merge(n, -1, Integer::sum) == 0) ready.add(n);
                }
            } catch (CancellationException e) {
                states.put(id, State.CANCELLED);
            } catch (ExecutionException e) {
                states.put(id, State.FAILED);
                reasons.put(id, e.getCause().getMessage());
                timeline.add(now.getAsLong() + "ms FAIL  " + id);
                Deque<String> stack = new ArrayDeque<>(dependents.getOrDefault(id, List.of()));
                while (!stack.isEmpty()) {
                    String n = stack.pop();
                    if (states.putIfAbsent(n, State.SKIPPED) == null) {
                        reasons.put(n, "依赖 " + id + " 失败");
                        ready.remove(n);
                        stack.addAll(dependents.getOrDefault(n, List.of()));
                    }
                }
                if (policy == Policy.FAIL_FAST) {
                    aborted = true;
                    for (var e2 : inFlight.entrySet()) {
                        e2.getValue().cancel(true);
                        states.put(e2.getKey(), State.CANCELLED);
                        reasons.put(e2.getKey(), "取消：" + id + " 失败");
                        timeline.add(now.getAsLong() + "ms cancel " + e2.getKey());
                    }
                    inFlight.clear();
                    for (String n : ready) {
                        states.putIfAbsent(n, State.SKIPPED);
                        reasons.putIfAbsent(n, "未启动：" + id + " 失败");
                    }
                    ready.clear();
                }
            }
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        for (String id : byId.keySet()) {
            if (!states.containsKey(id)) {
                states.put(id, State.SKIPPED);
                reasons.put(id, "未启动：前序失败");
            }
        }
        Map<String, State> ordered = new LinkedHashMap<>();
        byId.keySet().forEach(k -> ordered.put(k, states.get(k)));
        return new Result(ordered, reasons, now.getAsLong(), maxRunning.get(), timeline);
    }

    static Callable<Void> sleep(Node n, String failId) {
        return () -> {
            Thread.sleep(n.millis());
            if (n.id().equals(failId)) throw new IllegalStateException(n.id() + " 调用计费服务失败");
            return null;
        };
    }

    // 报名流程：资格校验 → 名额预留 → 费用计算、通知准备、风控复核 → 提交
    static final List<Node> SIGNUP = List.of(
            new Node("资格校验", 100, List.of()),
            new Node("名额预留", 100, List.of("资格校验")),
            new Node("费用计算", 200, List.of("名额预留")),
            new Node("通知准备", 200, List.of("名额预留")),
            new Node("风控复核", 200, List.of("名额预留")),
            new Node("提交", 50, List.of("费用计算", "通知准备", "风控复核")));

    public static void main(String[] args) throws Exception {
        // 1. 发布前校验
        List<Node> broken = List.of(
                new Node("资格校验", 0, List.of("名额预留")),
                new Node("名额预留", 0, List.of("资格校验")),
                new Node("费用计算", 0, List.of("名额预留")),
                new Node("提交", 0, List.of("费用计算", "发票")),
                new Node("通知准备", 0, List.of()),
                new Node("通知准备", 0, List.of()));
        System.out.println("validate\t" + String.join("；", validate(broken)));
        System.out.println("validate.ok\t正常的报名图：" + (validate(SIGNUP).isEmpty() ? "没有错误" : validate(SIGNUP)));

        // 2. 稳定顺序：同一张图，节点声明顺序不同
        List<Node> reversed = new ArrayList<>(SIGNUP);
        Collections.reverse(reversed);
        System.out.println("order.fifo\t普通队列：声明顺序 " + order(SIGNUP, false) + "，倒序声明 " + order(reversed, false));
        System.out.println("order.sorted\t按名称排序的 ready 集合：声明顺序 " + order(SIGNUP, true) + "，倒序声明 " + order(reversed, true));

        // 3. 并发上限
        long serial = SIGNUP.stream().mapToLong(Node::millis).sum();
        for (int limit : new int[] {1, 2, 3}) {
            Result r = run(SIGNUP, limit, Policy.SKIP_DEPENDENTS, n -> sleep(n, ""));
            System.out.printf("parallel.%d\t并发上限 %d：总耗时 %dms，同时运行最多 %d 个，全部成功=%s%n", limit, limit, r.makespanMs(),
                    r.maxRunning(), r.states().values().stream().allMatch(s -> s == State.SUCCEEDED));
        }
        System.out.println("parallel.serial\t各节点耗时之和 " + serial + "ms；关键路径 资格校验 → 名额预留 → 任一 200ms 分支 → 提交 = 450ms");

        // 4. 失败传播：费用计算 150ms 时失败（比其他分支短，失败时其他分支还在运行）
        List<Node> failing = SIGNUP.stream().map(n -> n.id().equals("费用计算") ? new Node(n.id(), 150, n.deps()) : n).toList();
        for (Policy p : Policy.values()) {
            Result r = run(failing, 3, p, n -> sleep(n, "费用计算"));
            System.out.printf("failure.%s\t%s：%s；原因 %s%n", p, p == Policy.SKIP_DEPENDENTS ? "跳过后继" : "立即取消",
                    r.states(), r.reasons());
        }

        // 5. 重试与幂等：名额预留第一次调用时，库存服务已经扣减，但响应超时
        for (boolean withKey : new boolean[] {false, true}) {
            AtomicInteger seatsTaken = new AtomicInteger();
            Set<String> seen = ConcurrentHashMap.newKeySet();
            AtomicInteger attempts = new AtomicInteger();
            String runId = "run-20260927-001";
            Function<Node, Callable<Void>> work = n -> () -> {
                if (!n.id().equals("名额预留")) return null;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    attempts.incrementAndGet();
                    String key = runId + ":" + n.id();
                    if (!withKey || seen.add(key)) seatsTaken.incrementAndGet();   // 库存服务：有键时按键去重
                    if (attempt == 1) continue;                                   // 第一次：已扣减但响应超时，重试
                    return null;
                }
                return null;
            };
            Result r = run(SIGNUP, 3, Policy.SKIP_DEPENDENTS, work);
            System.out.printf("retry.%s\t%s：名额预留调用 %d 次，实际占用名额 %d 个，全部成功=%s%n", withKey ? "key" : "nokey",
                    withKey ? "带幂等键（run_id + 节点）" : "不带幂等键", attempts.get(), seatsTaken.get(),
                    r.states().values().stream().allMatch(s -> s == State.SUCCEEDED));
        }
    }
}
