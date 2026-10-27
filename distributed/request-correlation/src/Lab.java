import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 一条连接上的异步请求怎样对上响应：共享完成对象、先发送后登记、超时清理与编号复用、连接断开、写出完成与业务结果。
 * 传输层是可控的内存实现，响应的到达顺序由测试脚本逐步驱动，输出确定。
 */
public class Lab {

    record Message(int id, String body) {}

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static String state(CompletableFuture<String> f) {
        if (!f.isDone()) {
            return "等待中";
        }
        try {
            return f.join();
        } catch (Exception e) {
            return "失败（" + e.getCause().getMessage() + "）";
        }
    }

    /** 朴素客户端：所有请求共用一个完成对象，相当于共用一个 CountDownLatch 与一个结果字段。 */
    static final class SharedClient {
        CompletableFuture<String> shared = new CompletableFuture<>();
        final List<Message> sent = new ArrayList<>();

        CompletableFuture<String> call(String body) {
            sent.add(new Message(sent.size() + 1, body));
            return shared;
        }

        void onResponse(Message m) {
            shared.complete(m.body());
        }
    }

    /** 待完成表：请求编号 → 独立的完成对象。 */
    static final class Client {
        final Map<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
        final Consumer<Message> wire;
        final boolean registerBeforeSend;
        final boolean removeOnTimeout;
        final int idSpace;
        int nextId;
        int unknown;

        Client(Consumer<Message> wire, boolean registerBeforeSend, boolean removeOnTimeout, int idSpace) {
            this.wire = wire;
            this.registerBeforeSend = registerBeforeSend;
            this.removeOnTimeout = removeOnTimeout;
            this.idSpace = idSpace;
        }

        CompletableFuture<String> call(String body) {
            int id = nextId;
            nextId = (nextId + 1) % idSpace;
            var f = new CompletableFuture<String>();
            if (registerBeforeSend) {
                pending.put(id, f);
                wire.accept(new Message(id, body));
            } else {
                wire.accept(new Message(id, body));
                pending.put(id, f);
            }
            return f;
        }

        void onResponse(Message m) {
            var f = pending.remove(m.id());
            if (f == null) {
                unknown++;
                return;
            }
            f.complete(m.body());
        }

        void expire(int id) {
            var f = removeOnTimeout ? pending.remove(id) : pending.get(id);
            if (f != null) {
                f.completeExceptionally(new RuntimeException("超时"));
            }
        }

        void connectionClosed() {
            pending.values().forEach(f -> f.completeExceptionally(new RuntimeException("连接已断开")));
            pending.clear();
        }
    }

    public static void main(String[] args) {
        // 1. 共用一个完成对象
        SharedClient shared = new SharedClient();
        var a = shared.call("查询余额 A");
        var b = shared.call("查询余额 B");
        shared.onResponse(new Message(2, "B 的余额 300"));
        shared.onResponse(new Message(1, "A 的余额 100"));
        out("shared", "两个请求共用一个完成对象，B 的响应先到：A 拿到「" + state(a) + "」，B 拿到「" + state(b) + "」");

        List<Message> wire = new ArrayList<>();
        Client c = new Client(wire::add, true, true, Integer.MAX_VALUE);
        var a2 = c.call("查询余额 A");
        var b2 = c.call("查询余额 B");
        c.onResponse(new Message(wire.get(1).id(), "B 的余额 300"));
        c.onResponse(new Message(wire.get(0).id(), "A 的余额 100"));
        out("pending", "按请求编号登记独立的完成对象，响应乱序到达：A 拿到「" + state(a2) + "」，B 拿到「" + state(b2) + "」");

        // 2. 先发送、后登记：同进程的对端在 send 里同步回复，模拟响应比登记先到
        Client[] holder = new Client[1];
        Client late = new Client(m -> holder[0].onResponse(new Message(m.id(), "pong")), false, true, Integer.MAX_VALUE);
        holder[0] = late;
        var p = late.call("ping");
        out("register.after-send", "先发送再登记，响应在登记前到达：调用方 " + state(p) + "，被当成未知编号丢弃的响应 " + late.unknown + " 个，表中残留 " + late.pending.size() + " 项");
        Client[] holder2 = new Client[1];
        Client early = new Client(m -> holder2[0].onResponse(new Message(m.id(), "pong")), true, true, Integer.MAX_VALUE);
        holder2[0] = early;
        var p2 = early.call("ping");
        out("register.before-send", "先登记再发送：调用方拿到「" + state(p2) + "」，表中残留 " + early.pending.size() + " 项");

        // 3. 超时后不清理：表项泄漏
        List<Message> wire3 = new ArrayList<>();
        Client leaky = new Client(wire3::add, true, false, Integer.MAX_VALUE);
        Client tidy = new Client(m -> {}, true, true, Integer.MAX_VALUE);
        for (int i = 0; i < 1000; i++) {
            leaky.call("慢请求");
            leaky.expire(i);
            tidy.call("慢请求");
            tidy.expire(i);
        }
        out("timeout.leak", "1000 个请求都超时：超时时不删表项，表中残留 " + leaky.pending.size() + " 项；删除表项，残留 " + tidy.pending.size() + " 项");

        // 4. 编号空间很小、编号被复用：迟到的响应完成了下一个请求
        List<Message> wire4 = new ArrayList<>();
        Client small = new Client(wire4::add, true, true, 4);
        var first = small.call("订单 1 的状态");
        small.expire(0);
        List<CompletableFuture<String>> rest = new ArrayList<>();
        for (int i = 2; i <= 4; i++) {
            rest.add(small.call("订单 " + i + " 的状态"));
        }
        var fifth = small.call("订单 5 的状态"); // 编号 0 被复用
        small.onResponse(new Message(0, "订单 1：已发货")); // 订单 1 的响应迟到
        out("timeout.reuse", "编号只有 4 个、按 0..3 循环：订单 1 超时后，复用编号 0 的订单 5 收到了「" + state(fifth) + "」；订单 1 自己是 " + state(first));
        List<Message> wire5 = new ArrayList<>();
        Client wide = new Client(wire5::add, true, true, Integer.MAX_VALUE);
        var w1 = wide.call("订单 1 的状态");
        wide.expire(0);
        for (int i = 2; i <= 4; i++) {
            wide.call("订单 " + i + " 的状态");
        }
        var w5 = wide.call("订单 5 的状态");
        wide.onResponse(new Message(0, "订单 1：已发货"));
        out("timeout.unique", "编号在连接生命周期内不复用：订单 1 的迟到响应被当成未知编号丢弃（" + wide.unknown + " 个），订单 5 仍在" + state(w5) + "，订单 1 是 " + state(w1));

        // 5. 连接断开
        List<Message> wire6 = new ArrayList<>();
        Client conn = new Client(wire6::add, true, true, Integer.MAX_VALUE);
        List<CompletableFuture<String>> inflight = List.of(conn.call("请求 1"), conn.call("请求 2"), conn.call("请求 3"));
        out("close.naive", "连接断开但没有通知待完成表：3 个请求都在" + state(inflight.get(0)) + "，要等各自超时");
        conn.connectionClosed();
        out("close.fail-all", "连接断开时把表里的请求全部失败：" + state(inflight.get(0)) + "，表中残留 " + conn.pending.size() + " 项");

        // 6. 写出完成不是业务结果
        List<Message> dropped = new ArrayList<>();
        Client one = new Client(m -> dropped.add(m), true, true, Integer.MAX_VALUE);
        CompletableFuture<Void> written = CompletableFuture.completedFuture(null); // 写进套接字缓冲区就算完成
        var biz = one.call("扣减库存");
        one.expire(0);
        out("write.vs-reply", "写出 Future 成功=" + written.isDone() + "，对端进程在处理前崩溃，业务结果：" + state(biz) + "（扣减可能做了也可能没做）");
    }
}
