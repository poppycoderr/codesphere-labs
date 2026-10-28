package lab;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import agent.Advices;
import agent.Agent;
import agent.Rec;
import lab.biz.Biz;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * 插桩代码不能改变业务结果：每个场景给一个业务类装上 advice，比较业务结果与采集结果。
 * 用 -javaagent 启动，Agent 只保存 Instrumentation，转换在这里按场景安装。
 */
public class Main {

    static final List<String> ERRORS = new ArrayList<>();

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static AgentBuilder builder() {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader cl, JavaModule module, boolean loaded, Throwable t) {
                        ERRORS.add(typeName.substring(typeName.lastIndexOf('$') + 1) + "：" + t.getClass().getSimpleName() + "：" + t.getMessage());
                    }
                });
    }

    static ResettableClassFileTransformer install(Class<?> advice, Class<?> type, ElementMatcher<? super MethodDescription> on) {
        return builder().type(named(type.getName())).transform((b, td, cl, m, pd) -> b.visit(Advice.to(advice).on(on))).installOn(Agent.inst);
    }

    /** 按类名安装，不触发类加载：构造器场景要让类在转换器安装之后才加载。 */
    static ResettableClassFileTransformer installByName(Class<?> advice, String type, ElementMatcher<? super MethodDescription> on) {
        return builder().type(named(type)).transform((b, td, cl, m, pd) -> b.visit(Advice.to(advice).on(on))).installOn(Agent.inst);
    }

    static String call(java.util.concurrent.Callable<?> c) {
        try {
            return "返回 " + c.call();
        } catch (Exception e) {
            return "抛出 " + e.getClass().getSimpleName() + "：" + e.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        // 一、advice 自己抛异常
        install(Advices.EnterThrows.class, Biz.Price.class, named("total"));
        out("advice.throws", "进入通知抛异常、没有 suppress：业务方法 total(3) " + call(() -> new Biz.Price().total(3)));
        install(Advices.EnterThrowsSuppressed.class, Biz.PriceSafe.class, named("total"));
        out("advice.suppress", "同样的通知加 suppress = Throwable.class：total(3) " + call(() -> new Biz.PriceSafe().total(3)));

        // 二、方法以异常结束时，出口通知是否执行
        Rec.reset();
        install(Advices.Count.class, Biz.Refund.class, named("refund"));
        String r1 = call(() -> new Biz.Refund().refund(10));
        String r2 = call(() -> new Biz.Refund().refund(-1));
        out("exit.default", "出口通知默认写法：refund(10) " + r1 + "，refund(-1) " + r2 + "；进入 " + Rec.ENTER.get() + " 次、出口 " + Rec.EXIT.get() + " 次");
        Rec.reset();
        install(Advices.CountOnThrowable.class, Biz.RefundOnThrowable.class, named("refund"));
        String r3 = call(() -> new Biz.RefundOnThrowable().refund(-1));
        out("exit.onThrowable", "onThrowable = Throwable.class：refund(-1) " + r3 + "；进入 " + Rec.ENTER.get() + " 次、出口 " + Rec.EXIT.get() + " 次，通知看到的异常 "
                + Rec.thrown.getClass().getSimpleName() + "，原异常照常抛给调用方");

        // 三、基本类型返回与构造器
        Rec.reset();
        install(Advices.ReturnValue.class, Biz.Prim.class, named("sum").or(named("nothing")));
        new Biz.Prim().sum(41);
        new Biz.Prim().nothing(1);
        out("return.primitive", "用 @Return(typing = DYNAMIC) Object 读返回值：long 方法得到 " + Rec.LOG.get(0) + "；void 方法得到 " + Rec.LOG.get(1));
        Rec.reset();
        ERRORS.clear();
        installByName(Advices.CtorThisOnEnter.class, "lab.biz.Biz$Order", isConstructor());
        Object o = new Biz.Order();
        out("ctor.enter", "构造器的进入通知读取 @This：类照常加载、对象创建成功=" + (o != null) + "，通知执行 " + Rec.LOG.size() + " 次；转换错误只交给监听器：" + ERRORS);
        Rec.reset();
        installByName(Advices.CtorThisOnExit.class, "lab.biz.Biz$Order2", isConstructor());
        new Biz.Order2();
        out("ctor.exit", "改为出口通知读取 @This：" + Rec.LOG);

        // 四、retransform、removeTransformer 与 reset
        Biz.Loaded loaded = new Biz.Loaded();
        loaded.ping(1);                                                   // 先加载、先执行
        Rec.reset();
        ResettableClassFileTransformer t = install(Advices.Count.class, Biz.Loaded.class, named("ping"));
        loaded.ping(1);
        int afterInstall = Rec.ENTER.get();
        boolean removed = Agent.inst.removeTransformer(t);
        loaded.ping(1);
        int afterRemove = Rec.ENTER.get();
        Agent.inst.retransformClasses(Biz.Loaded.class);
        loaded.ping(1);
        int afterRetransform = Rec.ENTER.get();
        out("retransform", "类已加载后安装：同一个对象的下一次调用就计数（累计 " + afterInstall + "）；removeTransformer 返回 " + removed + "，之后调用仍计数（累计 " + afterRemove
                + "）；再对这个类调用 retransformClasses 才恢复原样（累计 " + afterRetransform + "）");
        Biz.Loaded2 l2 = new Biz.Loaded2();
        l2.ping(1);
        Rec.reset();
        ResettableClassFileTransformer t2 = install(Advices.Count.class, Biz.Loaded2.class, named("ping"));
        l2.ping(1);
        int before2 = Rec.ENTER.get();
        boolean reset = t2.reset(Agent.inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        l2.ping(1);
        boolean resetAgain = t.reset(Agent.inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        out("retransform.reset", "ByteBuddy 的 reset 把移除与重新转换合在一起：reset 返回 " + reset + "，之后不再计数（累计 " + before2 + " → " + Rec.ENTER.get()
                + "）；对已经 removeTransformer 过的转换器再 reset 返回 " + resetAgain + "（什么也不做）");

        new Biz.Schema().ping(1);
        String schemaErr = "无";
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_LAST)
                    .type(named(Biz.Schema.class.getName()))
                    .transform((b, td, cl, m, pd) -> b.defineField("traceId", String.class, Visibility.PUBLIC))
                    .installOn(Agent.inst);
        } catch (Exception e) {
            Throwable c = e;
            while (c.getCause() != null) {
                c = c.getCause();
            }
            schemaErr = c.getClass().getSimpleName() + "：" + c.getMessage();
        }
        out("retransform.schema", "对已加载的类 retransform 时加一个字段：" + schemaErr);

        // 五、同一个类被转换两次
        Rec.reset();
        install(Advices.Count.class, Biz.Twice.class, named("run"));
        install(Advices.Count.class, Biz.Twice.class, named("run"));
        new Biz.Twice().run(1);
        out("twice", "两个转换器都给 run 装了计数通知（例如两个 agent 都插桩同一个框架）：调用 1 次，计数 " + Rec.ENTER.get());

        // 六、采集参数：截断与按模式遮挡
        Rec.reset();
        install(Advices.Capture.class, Biz.Pay.class, named("pay"));
        install(Advices.Capture.class, Biz.Login.class, named("login"));
        new Biz.Pay().pay("1234567812345678");
        new Biz.Login().login("example_password_with_suffix");
        out("capture", "截断到 16 个字符并遮掉数字：卡号参数采到 " + Rec.LOG.get(0) + "；密码参数采到 " + Rec.LOG.get(1));

        // 七、上报队列满
        install(Advices.Report.class, Biz.Pay.class, named("pay"));
        Rec.queue = new ArrayBlockingQueue<>(5);
        Rec.blocking = true;
        AtomicInteger done = new AtomicInteger();
        Thread caller = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                new Biz.Pay().pay("p" + i);
                done.incrementAndGet();
            }
        });
        caller.start();
        caller.join(500);
        int blockedAt = done.get();
        boolean stuck = caller.isAlive();
        Rec.queue.clear();
        caller.join(2000);
        out("queue.blocking", "上报队列容量 5、没有消费者，上报用 put：业务调用完成 " + blockedAt + " 次后卡住=" + stuck);
        Rec.blocking = false;
        Rec.queue = new ArrayBlockingQueue<>(5);
        Rec.DROPPED.set(0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            new Biz.Pay().pay("p" + i);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        out("queue.drop", "改用 offer、满了丢弃并计数：20 次业务调用全部完成（" + (ms < 500 ? "小于 500ms" : ms + "ms") + "），队列里 " + Rec.queue.size() + " 条，丢弃 " + Rec.DROPPED.get() + " 条");
        System.exit(0);
    }
}
