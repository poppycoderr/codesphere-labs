import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryForever;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;

/**
 * ZooKeeper 会话不是 TCP 连接：用一个可以「冻结」的 TCP 代理制造断连，观察会话、临时节点、Curator 连接状态、
 * 锁持有判断与 watch 的行为。冻结时代理既不转发也不关闭连接，客户端收不到心跳回复，服务端也收不到心跳。
 */
public class ZkSession {

    static final int TIMEOUT = 6000;
    static final long T0 = System.nanoTime();

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    /** 可冻结的 TCP 代理：冻结期间丢弃两个方向的数据，且不接受新连接的数据转发。 */
    static final class Proxy implements AutoCloseable {
        final ServerSocket server;
        volatile boolean frozen;
        final List<Socket> sockets = new CopyOnWriteArrayList<>();

        Proxy() throws IOException {
            server = new ServerSocket(0);
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket c = server.accept();
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress("zk", 2181));
                        sockets.add(c);
                        sockets.add(s);
                        pipe(c, s);
                        pipe(s, c);
                    } catch (IOException e) {
                        // 代理关闭
                    }
                }
            });
        }

        void pipe(Socket from, Socket to) {
            Thread.ofVirtual().start(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream o = to.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (!frozen) {
                            o.write(buf, 0, n);
                            o.flush();
                        }
                    }
                } catch (IOException e) {
                    // 连接关闭
                }
                try {
                    to.close();
                } catch (IOException ignored) {
                    // 已关闭
                }
            });
        }

        String address() {
            return "127.0.0.1:" + server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : sockets) {
                s.close();
            }
        }
    }

    static CuratorFramework curator(String connect, List<String> states) {
        CuratorFramework c = CuratorFrameworkFactory.builder().connectString(connect).sessionTimeoutMs(TIMEOUT).connectionTimeoutMs(3000)
                .retryPolicy(new RetryForever(200)).build();
        long start = System.nanoTime();
        c.getConnectionStateListenable().addListener((cl, s) -> states.add(s + "@" + Math.round((System.nanoTime() - start) / 1e8) / 10.0));
        c.start();
        return c;
    }

    static double now() {
        return Math.round((System.nanoTime() - T0) / 1e8) / 10.0;
    }

    /** 观察者直接连 ZooKeeper，从这里判断临时节点是否还在。 */
    static double waitGone(CuratorFramework observer, String path, double from, int maxMillis) throws Exception {
        long end = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < end) {
            if (observer.checkExists().forPath(path) == null) {
                return Math.round((now() - from) * 10) / 10.0;
            }
            Thread.sleep(100);
        }
        return -1;
    }

    static List<String> names(List<String> states) {
        List<String> r = new ArrayList<>();
        for (String s : states) {
            r.add(s.substring(0, s.indexOf('@')));
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        CuratorFramework observer = curator("zk:2181", new ArrayList<>());
        observer.blockUntilConnected();
        out("env", "zookeeper 客户端 " + org.apache.zookeeper.Version.getFullVersion().split("-")[0].split(" ")[0] + "，会话超时 " + TIMEOUT + "ms");

        // 一、短暂断连：冻结 3 秒，短于会话超时
        try (Proxy p = new Proxy()) {
            List<String> st = new CopyOnWriteArrayList<>();
            CuratorFramework c = curator(p.address(), st);
            c.blockUntilConnected();
            long sid = c.getZookeeperClient().getZooKeeper().getSessionId();
            c.create().withMode(CreateMode.EPHEMERAL).forPath("/a");
            p.frozen = true;
            Thread.sleep(3000);
            boolean during = observer.checkExists().forPath("/a") != null;
            p.frozen = false;
            p.sockets.forEach(s -> {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 断开旧连接，促使客户端重连
                }
            });
            Thread.sleep(3000);
            long sid2 = c.getZookeeperClient().getZooKeeper().getSessionId();
            out("short.states", "冻结 3 秒（会话超时 6 秒）：连接状态 " + names(st));
            out("short.session", "断连期间临时节点还在=" + during + "，重连后仍在=" + (observer.checkExists().forPath("/a") != null)
                    + "，会话 ID 不变=" + (sid == sid2));
            c.close();
        }

        // 二、长时间断连：冻结 12 秒；临时节点在会话超时后才消失
        try (Proxy p = new Proxy()) {
            List<String> st = new CopyOnWriteArrayList<>();
            CuratorFramework c = curator(p.address(), st);
            c.blockUntilConnected();
            long sid = c.getZookeeperClient().getZooKeeper().getSessionId();
            c.create().withMode(CreateMode.EPHEMERAL).forPath("/b");
            double frozenAt = now();
            p.frozen = true;
            double gone = waitGone(observer, "/b", frozenAt, 15000);
            Thread.sleep(Math.max(0, 12000 - (long) ((now() - frozenAt) * 1000)));
            p.frozen = false;
            p.sockets.forEach(s -> {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 断开旧连接
                }
            });
            Thread.sleep(4000);
            long sid2 = c.getZookeeperClient().getZooKeeper().getSessionId();
            out("long.gone", "冻结后临时节点消失的时刻在会话超时附近（5 到 9 秒）=" + (gone >= 5 && gone <= 9) + "；不是断连的瞬间=" + (gone > 1));
            out("long.states", "冻结 12 秒：连接状态 " + names(st) + "，恢复后是新的会话=" + (sid != sid2) + "，/b 不会自动重建=" + (observer.checkExists().forPath("/b") == null));
            System.err.println("long_gone_after=" + gone + " states=" + st);
            c.close();
        }

        // 三、正常关闭与进程消失
        {
            CuratorFramework c = curator("zk:2181", new ArrayList<>());
            c.blockUntilConnected();
            c.create().withMode(CreateMode.EPHEMERAL).forPath("/c");
            double t = now();
            c.close();
            double closed = waitGone(observer, "/c", t, 10000);
            Proxy p = new Proxy();
            CuratorFramework d = curator(p.address(), new ArrayList<>());
            d.blockUntilConnected();
            d.create().withMode(CreateMode.EPHEMERAL).forPath("/d");
            t = now();
            p.frozen = true;                                   // 模拟进程被 kill -9：不再有任何报文
            double crashed = waitGone(observer, "/d", t, 15000);
            out("close.vs.crash", "close() 后临时节点 1 秒内消失=" + (closed >= 0 && closed < 1) + "；进程消失后要等到会话超时（大于 5 秒）=" + (crashed > 5));
            System.err.println("close_gone_after=" + closed + " crash_gone_after=" + crashed);
            d.close();
            p.close();
        }

        // 四、锁：持有者失联，会话过期后另一个客户端拿到锁
        try (Proxy p = new Proxy()) {
            List<String> st = new CopyOnWriteArrayList<>();
            CuratorFramework c1 = curator(p.address(), st);
            c1.blockUntilConnected();
            InterProcessMutex m1 = new InterProcessMutex(c1, "/lock");
            m1.acquire();
            p.frozen = true;
            CuratorFramework c2 = curator("zk:2181", new ArrayList<>());
            c2.blockUntilConnected();
            InterProcessMutex m2 = new InterProcessMutex(c2, "/lock");
            boolean got = m2.acquire(15, TimeUnit.SECONDS);
            out("lock.lost", "持有者失联：另一个客户端拿到了锁=" + got + "；此时原持有者 isAcquiredInThisProcess()=" + m1.isAcquiredInThisProcess()
                    + "，它收到的连接状态 " + names(st));
            m2.release();
            c2.close();
            p.frozen = false;
            c1.close();
        }

        // 五、watch：一次性的 watch 与持久递归 watch
        {
            ZooKeeper zk = observer.getZookeeperClient().getZooKeeper();
            observer.create().forPath("/w", "v0".getBytes());
            AtomicInteger once = new AtomicInteger();
            List<String> onceTypes = new CopyOnWriteArrayList<>();
            zk.getData("/w", e -> {
                once.incrementAndGet();
                onceTypes.add(e.getType().name());
            }, null);
            AtomicInteger persistent = new AtomicInteger();
            CountDownLatch two = new CountDownLatch(2);
            zk.addWatch("/w", e -> {
                if (e.getType() == org.apache.zookeeper.Watcher.Event.EventType.NodeDataChanged) {
                    persistent.incrementAndGet();
                    two.countDown();
                }
            }, AddWatchMode.PERSISTENT_RECURSIVE);
            observer.setData().forPath("/w", "v1".getBytes());
            observer.setData().forPath("/w", "v2".getBytes());
            two.await(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            out("watch", "连续改两次：一次性 watch 收到 " + once.get() + " 个事件 " + onceTypes + "（事件里没有新值），持久递归 watch 收到 " + persistent.get()
                    + " 个；之后读到的值是 " + new String(observer.getData().forPath("/w")));
        }
        observer.close();
    }
}
