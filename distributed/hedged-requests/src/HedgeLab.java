import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 备份请求（hedged request）：一个请求在规定时间内没有返回，就向另一个实例再发一份，取先回来的那个。
 * 离散事件模拟：10 个实例，每个 8 个处理槽；98% 的请求处理 10 毫秒，2% 的请求处理 300 毫秒（与实例无关的偶发慢）。固定种子，输出确定。
 */
public class HedgeLab {
    static final int N = 10, SLOTS = 8;
    static final double DURATION = 30, FAST = 0.010, SLOW = 0.300, SLOW_SHARE = 0.02;

    static final class Req { final double arrived; boolean done; int copiesFinished, copiesSent; Req(double t) { arrived = t; } }
    static final class Instance { int busy, inFlight; final ArrayDeque<Req> waiting = new ArrayDeque<>(); }
    record Event(double time, int kind, int instance, Req req) {}   // 0 到达，1 完成，2 备份定时器

    /** hedgeDelay < 0 表示不发备份；budget 是备份请求占全部请求的比例上限（1 表示不限） */
    static String run(double rate, double hedgeDelay, double budget) {
        Random rnd = new Random(20261010L);
        Instance[] inst = new Instance[N];
        for (int i = 0; i < N; i++) inst[i] = new Instance();
        PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> Double.compare(a.time, b.time));
        events.add(new Event(-Math.log(1 - rnd.nextDouble()) / rate, 0, -1, null));
        java.util.List<Double> lat = new java.util.ArrayList<>();
        long total = 0, hedges = 0, executed = 0, twice = 0, denied = 0;
        while (!events.isEmpty()) {
            Event e = events.poll();
            if (e.kind == 0) {
                if (e.time > DURATION) continue;
                events.add(new Event(e.time - Math.log(1 - rnd.nextDouble()) / rate, 0, -1, null));
                Req r = new Req(e.time); total++;
                send(inst, pick(inst, rnd, -1), r, e.time, events, rnd);
                if (hedgeDelay >= 0) events.add(new Event(e.time + hedgeDelay, 2, -1, r));
            } else if (e.kind == 2) {
                if (e.req.done) continue;
                if (hedges + 1 > budget * total) { denied++; continue; }
                hedges++;
                send(inst, pick(inst, rnd, -1), e.req, e.time, events, rnd);
            } else {
                Instance t = inst[e.instance];
                t.inFlight--; executed++;
                Req r = e.req;
                if (++r.copiesFinished == 2) twice++;
                if (!r.done) { r.done = true; lat.add(e.time - r.arrived); }
                Req next = t.waiting.poll();
                if (next != null) events.add(new Event(e.time + serviceTime(rnd), 1, e.instance, next));
                else t.busy--;
            }
        }
        double[] l = lat.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return String.format("p50 %.0f ms\tp99 %.0f ms\tp99.9 %.0f ms\t备份请求占 %.1f%%\t服务端实际执行次数是请求数的 %.2f 倍\t两份都执行完的请求占 %.1f%%%s",
                1000 * pct(l, 50), 1000 * pct(l, 99), 1000 * pct(l, 99.9), 100.0 * hedges / total, (double) executed / total, 100.0 * twice / total,
                budget < 1 ? String.format("\t因预算用完而没发的备份 %d 次", denied) : "");
    }

    static double serviceTime(Random rnd) { return rnd.nextDouble() < SLOW_SHARE ? SLOW : FAST; }

    /** 两次随机选择：随机挑两个实例，取在途较少的 */
    static int pick(Instance[] inst, Random rnd, int exclude) {
        int a = rnd.nextInt(N), b = rnd.nextInt(N);
        return inst[a].inFlight <= inst[b].inFlight ? a : b;
    }

    static void send(Instance[] inst, int target, Req r, double now, PriorityQueue<Event> events, Random rnd) {
        Instance t = inst[target];
        t.inFlight++; r.copiesSent++;
        if (t.busy < SLOTS) { t.busy++; events.add(new Event(now + serviceTime(rnd), 1, target, r)); }
        else t.waiting.add(r);
    }

    static double pct(double[] sorted, double p) { return sorted[(int) Math.min(sorted.length - 1, Math.ceil(sorted.length * p / 100.0) - 1)]; }

    public static void main(String[] args) {
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        System.out.println("setup\t10 个实例各 8 个处理槽；98% 的请求处理 10 ms，2% 处理 300 ms，平均 15.8 ms，总容量约 5060 请求/秒；模拟 30 秒");
        for (int rate : new int[]{2500, 4500}) {
            String k = "rate_" + rate;
            System.out.println(k + ".none\t" + run(rate, -1, 1));
            System.out.println(k + ".hedge_30ms\t" + run(rate, 0.030, 1));
            System.out.println(k + ".hedge_30ms_budget_5\t" + run(rate, 0.030, 0.05));
            System.out.println(k + ".hedge_immediately\t" + run(rate, 0, 1));
        }
    }
}
