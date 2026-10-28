import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;

/**
 * ByteBuf 的引用计数：谁最后使用谁释放。用 EmbeddedChannel 驱动处理器，观察每个场景结束时消息的 refCnt，
 * 以及 PARANOID 级别的泄漏检测报告。
 */
public class Lab {

    static final List<String> LEAKS = new CopyOnWriteArrayList<>();

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static ByteBuf msg(String s) {
        return ByteBufAllocator.DEFAULT.buffer().writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 消费了消息，既不释放也不往下传。 */
    static class SwallowHandler extends ChannelInboundHandlerAdapter {
        ByteBuf last;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object m) {
            last = (ByteBuf) m;
            last.readableBytes();
        }
    }

    /** 不合法的消息直接关闭连接，忘了释放。 */
    static class RejectHandler extends ChannelInboundHandlerAdapter {
        final boolean release;
        ByteBuf last;

        RejectHandler(boolean release) {
            this.release = release;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object m) {
            last = (ByteBuf) m;
            if (last.readableBytes() > 4) {
                if (release) {
                    ReferenceCountUtil.release(m);
                }
                ctx.close();
                return;
            }
            ctx.fireChannelRead(m);
        }
    }

    /** SimpleChannelInboundHandler 在 channelRead0 返回后自动释放；把消息交给别人时要先 retain。 */
    static class HandOff extends SimpleChannelInboundHandler<ByteBuf> {
        final boolean retain;
        final List<ByteBuf> queue = new ArrayList<>();

        HandOff(boolean retain) {
            this.retain = retain;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf m) {
            queue.add(retain ? m.retain() : m);
        }
    }

    static class Tag extends ChannelOutboundHandlerAdapter {
        final String name;
        final List<String> seen;

        Tag(String name, List<String> seen) {
            this.name = name;
            this.seen = seen;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object m, ChannelPromise p) {
            seen.add(name);
            ctx.write(m, p);
        }
    }

    public static void main(String[] args) throws Exception {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        Logger.getLogger(ResourceLeakDetector.class.getName()).addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getMessage() != null && r.getMessage().startsWith("LEAK")) {
                    LEAKS.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Logger.getLogger(ResourceLeakDetector.class.getName()).setUseParentHandlers(false);

        // 一、入站处理器吞掉消息：计数停在 1；对象被回收后泄漏检测报告访问位置
        swallow();
        int leaks = awaitLeaks(1);
        String report = LEAKS.isEmpty() ? "" : LEAKS.get(0);
        System.err.println(report);
        out("leak.swallow", "对象被回收后 PARANOID 级别报告泄漏 " + leaks + " 条；最近一条访问记录是管道写入的提示「SwallowHandler 从这里开始处理这条消息」="
                + report.contains("SwallowHandler#0' will handle the message from this point") + "；还记录了创建位置=" + report.contains("Created at:"));

        // 二、往下传：到达管道尾部时由 TailContext 释放；EmbeddedChannel 的尾部改为放进自己的入站队列
        out("tail.release", "真实通道（Local 传输）里处理器只调用 fireChannelRead、后面没有人处理：返回后 refCnt=" + forwardOverLocal());
        EmbeddedChannel pass = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ByteBuf p = msg("hello");
        pass.writeInbound(p);
        Object queued = pass.readInbound();
        out("tail.embedded", "同样的处理器放进 EmbeddedChannel：refCnt=" + p.refCnt() + "，消息进了 readInbound() 的队列=" + (queued == p) + "（测试里读出来之后要自己释放）");
        ReferenceCountUtil.release(queued);

        // 三、SimpleChannelInboundHandler 自动释放；转交前不 retain，之后读到的是已释放的对象
        HandOff noRetain = new HandOff(false);
        EmbeddedChannel a = new EmbeddedChannel(noRetain);
        a.writeInbound(msg("job-1"));
        ByteBuf kept = noRetain.queue.get(0);
        String later;
        try {
            later = kept.toString(StandardCharsets.UTF_8);
        } catch (IllegalReferenceCountException e) {
            later = "IllegalReferenceCountException";
        }
        out("simple.handoff", "SimpleChannelInboundHandler 把消息放进队列、没有 retain：channelRead0 返回后 refCnt=" + kept.refCnt() + "，队列消费者读取时 " + later);
        HandOff withRetain = new HandOff(true);
        EmbeddedChannel b = new EmbeddedChannel(withRetain);
        b.writeInbound(msg("job-2"));
        ByteBuf held = withRetain.queue.get(0);
        int before = held.refCnt();
        String read = held.toString(StandardCharsets.UTF_8);
        held.release();
        out("simple.retain", "转交前 retain：channelRead0 返回后 refCnt=" + before + "，消费者读到 " + read + "，用完 release 后 refCnt=" + held.refCnt());

        // 四、派生视图共享引用计数
        ByteBuf parent = msg("0123456789");
        ByteBuf slice = parent.slice(0, 4);
        ByteBuf dup = parent.duplicate();
        String shared = "slice.refCnt=" + slice.refCnt() + "，duplicate.refCnt=" + dup.refCnt();
        slice.release();
        out("derived.shared", "slice 与 duplicate 和原缓冲区共享计数（" + shared + "）：释放 slice 之后原缓冲区 refCnt=" + parent.refCnt());
        ByteBuf parent2 = msg("0123456789");
        ByteBuf rs = parent2.retainedSlice(0, 4);
        int afterRetainedSlice = parent2.refCnt();
        parent2.release();
        String stillReadable = rs.toString(StandardCharsets.UTF_8);
        rs.release();
        out("derived.retained", "retainedSlice 让原缓冲区 refCnt 变为 " + afterRetainedSlice + "；原持有者释放后切片仍可读（" + stillReadable + "），切片释放后原缓冲区 refCnt=" + parent2.refCnt());

        // 五、拒绝分支直接 close
        RejectHandler forgot = new RejectHandler(false);
        EmbeddedChannel c = new EmbeddedChannel(forgot);
        c.writeInbound(msg("too-long-message"));
        out("reject.close", "消息不合法时直接 ctx.close() 返回：连接已关闭=" + !c.isOpen() + "，消息 refCnt=" + forgot.last.refCnt() + "（没有人会再释放它）");
        forgot.last.release();
        RejectHandler fixed = new RejectHandler(true);
        EmbeddedChannel d = new EmbeddedChannel(fixed);
        d.writeInbound(msg("too-long-message"));
        out("reject.fixed", "关闭前先 ReferenceCountUtil.release：消息 refCnt=" + fixed.last.refCnt());

        // 六、出站：write 之后所有权交给 Netty，写失败也由 Netty 释放
        EmbeddedChannel e = new EmbeddedChannel();
        e.close();
        ByteBuf w = msg("late");
        ChannelFuture f = e.writeAndFlush(w);
        out("write.closed", "向已关闭的通道写：future 成功=" + f.isSuccess() + "（" + f.cause().getClass().getSimpleName() + "），消息 refCnt=" + w.refCnt() + "；调用方再 release 一次："
                + doubleRelease(w));

        // 七、出站处理器的起点：ctx.write 从当前位置往头部走，channel.write 从尾部开始
        List<String> viaCtx = new ArrayList<>();
        List<String> viaChannel = new ArrayList<>();
        EmbeddedChannel g = new EmbeddedChannel(new Tag("OutA", viaCtx), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object m) {
                ctx.writeAndFlush(m);
            }
        }, new Tag("OutB", viaCtx));
        g.writeInbound(msg("x"));
        ReferenceCountUtil.release(g.readOutbound());
        EmbeddedChannel h = new EmbeddedChannel(new Tag("OutA", viaChannel), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object m) {
                ctx.channel().writeAndFlush(m);
            }
        }, new Tag("OutB", viaChannel));
        h.writeInbound(msg("x"));
        ReferenceCountUtil.release(h.readOutbound());
        out("pipeline.order", "管道 [OutA, 入站处理器, OutB]：入站处理器里 ctx.write 经过 " + viaCtx + "，ctx.channel().write 经过 " + viaChannel);

        // 结束时没有新的泄漏：上面每个场景最后都释放干净了
        LEAKS.clear();
        int more = awaitLeaks(0);
        out("leak.after", "其余场景都按约定释放后，再次触发检测：泄漏报告 " + more + " 条");
    }

    static void swallow() {
        SwallowHandler h = new SwallowHandler();
        EmbeddedChannel ch = new EmbeddedChannel(h);
        ch.writeInbound(msg("swallowed"));
        System.out.println("leak.refcnt\t吞掉之后 refCnt=" + h.last.refCnt());
        h.last = null;
        ch.finishAndReleaseAll();
    }

    /** 服务端子通道只有一个往下传的处理器，测量 fireChannelRead 返回之后消息的计数。 */
    static int forwardOverLocal() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
        CompletableFuture<Integer> after = new CompletableFuture<>();
        try {
            LocalAddress addr = new LocalAddress("lab");
            new ServerBootstrap().group(group).channel(LocalServerChannel.class).childHandler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object m) {
                    ByteBuf b = (ByteBuf) m;
                    ctx.fireChannelRead(m);
                    after.complete(b.refCnt());
                }
            }).bind(addr).sync();
            Channel client = new Bootstrap().group(group).channel(LocalChannel.class).handler(new ChannelInboundHandlerAdapter()).connect(addr).sync().channel();
            client.writeAndFlush(msg("hello")).sync();
            return after.get(5, TimeUnit.SECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    static String doubleRelease(ByteBuf b) {
        try {
            b.release();
            return "无异常";
        } catch (IllegalReferenceCountException ex) {
            return "IllegalReferenceCountException";
        }
    }

    /** 泄漏只在对象被回收、且之后有新的分配触发检查时才报告。 */
    static int awaitLeaks(int want) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            System.gc();
            Thread.sleep(50);
            for (int j = 0; j < 10; j++) {
                ByteBufAllocator.DEFAULT.buffer(16).release();
            }
            if (want > 0 && LEAKS.size() >= want) {
                break;
            }
        }
        return LEAKS.size();
    }
}
