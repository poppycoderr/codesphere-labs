import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 负载均衡遇到异常实例：10 个实例里有 1 个变慢（或者很快地返回错误），比较几种选择策略把多少请求送给了它，以及整体的延迟与错误。
 * 离散事件模拟：请求按固定平均速率随机到达，每个实例有 8 个处理槽和一个先进先出的等待队列。固定种子，输出确定。
 */
public class LbLab {
    static final int N = 10, SLOTS = 8;
    static final double RATE = 4000, DURATION = 30, TIMEOUT = 1.0;      // 每秒请求数、模拟秒数、调用方超时（秒）
    static final double NORMAL = 0.010, SLOW = 0.100, FAIL_FAST = 0.001; // 每个请求的处理时间（秒）

    enum Policy { ROUND_ROBIN, RANDOM, LEAST_IN_FLIGHT, TWO_CHOICES, LEAST_IN_FLIGHT_WITH_EJECTION }
    enum Fault { SLOW, FAIL_FAST }

    static final class Instance {
        final double serviceTime; final boolean failing;
        int busy; final ArrayDeque<double[]> waiting = new ArrayDeque<>();   // 等待中的请求：[到达时刻]
        int inFlight, sent, consecutiveFailures; double ejectedUntil;
        Instance(double serviceTime, boolean failing) { this.serviceTime = serviceTime; this.failing = failing; }
    }

    record Event(double time, int kind, int instance, double arrivedAt) {}   // kind 0 = 到达，1 = 完成

    static String run(Policy policy, Fault fault) {
        Random rnd = new Random(20261010L);
        Instance[] inst = new Instance[N];
        for (int i = 0; i < N; i++) inst[i] = new Instance(NORMAL, false);
        inst[3] = fault == Fault.SLOW ? new Instance(SLOW, false) : new Instance(FAIL_FAST, true);
        PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> Double.compare(a.time, b.time));
        events.add(new Event(-Math.log(1 - rnd.nextDouble()) / RATE, 0, -1, 0));
        List<Double> latencies = new ArrayList<>();
        int rr = 0, total = 0, errors = 0, timeouts = 0, ejections = 0;
        while (!events.isEmpty()) {
            Event e = events.poll();
            if (e.kind == 0) {
                if (e.time > DURATION) continue;
                events.add(new Event(e.time - Math.log(1 - rnd.nextDouble()) / RATE, 0, -1, 0));
                int target = switch (policy) {
                    case ROUND_ROBIN -> rr++ % N;
                    case RANDOM -> rnd.nextInt(N);
                    case TWO_CHOICES -> { int a = rnd.nextInt(N), b = rnd.nextInt(N); yield inst[a].inFlight <= inst[b].inFlight ? a : b; }
                    case LEAST_IN_FLIGHT, LEAST_IN_FLIGHT_WITH_EJECTION -> {
                        int best = -1, ties = 0;
                        for (int i = 0; i < N; i++) {
                            if (policy == Policy.LEAST_IN_FLIGHT_WITH_EJECTION && inst[i].ejectedUntil > e.time) continue;
                            if (best < 0 || inst[i].inFlight < inst[best].inFlight) { best = i; ties = 1; }
                            else if (inst[i].inFlight == inst[best].inFlight && rnd.nextInt(++ties) == 0) best = i;   // 并列时随机取一个
                        }
                        yield best;
                    }
                };
                Instance t = inst[target];
                total++; t.sent++; t.inFlight++;
                if (t.busy < SLOTS) { t.busy++; events.add(new Event(e.time + t.serviceTime, 1, target, e.time)); }
                else t.waiting.add(new double[]{e.time});
            } else {
                Instance t = inst[e.instance];
                t.inFlight--;
                double latency = e.time - e.arrivedAt;
                latencies.add(latency);
                if (latency > TIMEOUT) timeouts++;
                if (t.failing) {
                    errors++;
                    if (policy == Policy.LEAST_IN_FLIGHT_WITH_EJECTION && ++t.consecutiveFailures >= 5 && t.ejectedUntil <= e.time) {
                        t.ejectedUntil = e.time + 5.0; t.consecutiveFailures = 0; ejections++;       // 连续 5 次失败：摘除 5 秒
                    }
                } else t.consecutiveFailures = 0;
                double[] next = t.waiting.poll();
                if (next != null) events.add(new Event(e.time + t.serviceTime, 1, e.instance, next[0]));
                else t.busy--;
            }
        }
        double[] l = latencies.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return String.format("送到异常实例 %.1f%%\tp50 %.0f ms\tp99 %.0f ms\t最大 %.1f s\t超过 1 秒 %.2f%%\t返回错误 %.2f%%%s",
                100.0 * inst[3].sent / total, 1000 * pct(l, 50), 1000 * pct(l, 99), l[l.length - 1], 100.0 * timeouts / total, 100.0 * errors / total,
                policy == Policy.LEAST_IN_FLIGHT_WITH_EJECTION ? "\t摘除 " + ejections + " 次" : "");
    }

    static double pct(double[] sorted, double p) { return sorted[(int) Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100.0) - 1)]; }

    public static void main(String[] args) {
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        System.out.println("setup\t" + N + " 个实例，每个 " + SLOTS + " 个处理槽、正常处理时间 10 ms（单实例上限 800 请求/秒）；到达速率 " + (int) RATE
                + " 请求/秒，模拟 " + (int) DURATION + " 秒；实例 3 异常：变慢时处理时间 100 ms（上限 80 请求/秒），快速失败时 1 ms 返回错误");
        for (Fault f : Fault.values())
            for (Policy p : Policy.values()) {
                if (f == Fault.SLOW && p == Policy.LEAST_IN_FLIGHT_WITH_EJECTION) continue;
                System.out.println(f.name().toLowerCase() + "." + p.name().toLowerCase() + "\t" + run(p, f));
            }
    }
}
