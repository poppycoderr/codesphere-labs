import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.rm.RMClient;
import org.apache.seata.rm.datasource.DataSourceProxy;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;

/**
 * Seata AT 模式：分支先本地提交，全局回滚靠前后镜像补偿。观察全局提交前的可见性、回滚恢复、绕过 Seata 的写入导致的回滚失败、
 * 全局锁冲突，以及全局事务超时后被协调器回滚。经 DataSourceProxy 的连接受 Seata 管理，raw 连接模拟不经过 Seata 的其他写入方。
 */
public class SeataAt {

    static DataSource raw;
    static DataSource proxy;

    static void out(String key, String text) {
        System.out.println(key + "\t" + text);
    }

    static int balance(int id) throws SQLException {
        try (Connection c = raw.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT balance FROM account WHERE id = " + id)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int undoRows() throws SQLException {
        try (Connection c = raw.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM undo_log")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static void rawExec(String sql) throws SQLException {
        try (Connection c = raw.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** 经 Seata 代理执行一条更新：在全局事务里，这一次本地提交就是一个分支。 */
    static void branch(String sql) throws SQLException {
        try (Connection c = proxy.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    static void reset() throws SQLException {
        rawExec("DELETE FROM undo_log");
        rawExec("REPLACE INTO account VALUES (1, 100), (2, 100), (3, 100)");
    }

    public static void main(String[] args) throws Exception {
        MysqlDataSource m = new MysqlDataSource();
        m.setURL("jdbc:mysql://mysql:3306/labs?useSSL=false&allowPublicKeyRetrieval=true");
        m.setUser("root");
        m.setPassword("example_password");
        raw = m;
        TMClient.init("lab-app", "default_tx_group");
        RMClient.init("lab-app", "default_tx_group");
        proxy = new DataSourceProxy(m);
        Thread.sleep(3000);                                         // 等 RM 向 TC 注册资源

        // 一、分支先本地提交：全局提交之前，其他连接已经能读到
        reset();
        GlobalTransaction tx = GlobalTransactionContext.getCurrentOrCreate();
        tx.begin(30_000, "transfer");
        branch("UPDATE account SET balance = balance - 30 WHERE id = 1");
        int seen = balance(1);
        int undo = undoRows();
        tx.commit();
        Thread.sleep(1500);                                         // 二阶段异步删除 undo_log
        out("commit.visible", "全局事务提交前，不经 Seata 的连接读到账户 1 的余额 " + seen + "（初始 100，分支扣了 30），undo_log " + undo + " 行");
        out("commit.done", "全局提交后：状态 " + tx.getStatus() + "，余额 " + balance(1) + "，undo_log " + undoRows() + " 行");

        // 二、全局回滚：按前镜像补偿
        reset();
        tx = GlobalTransactionContext.getCurrentOrCreate();
        tx.begin(30_000, "transfer");
        branch("UPDATE account SET balance = balance - 30 WHERE id = 1");
        branch("UPDATE account SET balance = balance + 30 WHERE id = 2");
        int mid1 = balance(1);
        int mid2 = balance(2);
        tx.rollback();
        out("rollback", "两个分支提交后余额 " + mid1 + "/" + mid2 + "；全局回滚：状态 " + tx.getStatus() + "，余额恢复为 " + balance(1) + "/" + balance(2));

        // 三、绕过 Seata 的写入改了同一行：后镜像对不上，回滚失败
        reset();
        tx = GlobalTransactionContext.getCurrentOrCreate();
        tx.begin(30_000, "transfer");
        branch("UPDATE account SET balance = balance - 30 WHERE id = 1");
        rawExec("UPDATE account SET balance = balance + 5 WHERE id = 1");   // 另一个系统直接改库
        String err = "无";
        try {
            tx.rollback();
        } catch (Exception e) {
            err = e.getClass().getSimpleName();
        }
        out("dirty.rollback", "分支提交后另一个连接把余额 +5，再全局回滚：状态 " + tx.getStatus() + "，异常 " + err + "，余额 " + balance(1)
                + "（不是初始的 100），undo_log 仍有 " + undoRows() + " 行");
        RootContext.unbind();
        // 回滚失败的全局事务不会释放全局锁：之后经 Seata 改这一行的事务拿不到锁
        GlobalTransaction after = GlobalTransactionContext.getCurrentOrCreate();
        after.begin(30_000, "after-dirty");
        String afterResult = "成功";
        try {
            branch("UPDATE account SET balance = balance - 1 WHERE id = 1");
        } catch (SQLException e) {
            afterResult = "失败（" + e.getClass().getSimpleName() + "）";
        }
        after.rollback();
        RootContext.unbind();
        out("dirty.lock", "回滚失败之后，新的全局事务经 Seata 改账户 1：" + afterResult + "（失败的全局事务仍持有这一行的全局锁）");

        // 四、全局锁：另一个全局事务改同一行，要等第一个结束
        reset();
        GlobalTransaction t1 = GlobalTransactionContext.getCurrentOrCreate();
        t1.begin(30_000, "t1");
        branch("UPDATE account SET balance = balance - 10 WHERE id = 2");
        String xid1 = RootContext.unbind();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> other = pool.submit(() -> {
            GlobalTransaction t2 = GlobalTransactionContext.getCurrentOrCreate();
            t2.begin(30_000, "t2");
            try {
                branch("UPDATE account SET balance = balance - 20 WHERE id = 2");
                t2.commit();
                return "成功";
            } catch (Exception e) {
                t2.rollback();
                Throwable c = e;
                while (c.getCause() != null) {
                    c = c.getCause();
                }
                return "失败（" + c.getClass().getSimpleName() + "）";
            }
        });
        String r2 = other.get();
        RootContext.bind(xid1);
        t1.commit();
        RootContext.unbind();
        pool.shutdown();
        out("global.lock", "全局事务 1 改了账户 2 尚未结束时，全局事务 2 改同一行：" + r2 + "；事务 1 提交后余额 " + balance(2));
        String direct = "成功";
        GlobalTransaction t3 = GlobalTransactionContext.getCurrentOrCreate();
        t3.begin(30_000, "t3");
        branch("UPDATE account SET balance = balance - 10 WHERE id = 2");
        String xid3 = RootContext.unbind();
        try (Connection c = raw.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE account SET balance = balance - 1 WHERE id = 2");
        } catch (SQLException e) {
            direct = "失败";
        }
        RootContext.bind(xid3);
        t3.commit();
        RootContext.unbind();
        out("global.lock.raw", "同样的情况下，不经 Seata 的连接直接改同一行：" + direct + "（全局锁只约束经过 Seata 的写入）");

        // 五、全局事务超时：协调器回滚，客户端随后提交得到的是回滚结果
        reset();
        tx = GlobalTransactionContext.getCurrentOrCreate();
        tx.begin(2000, "slow");
        branch("UPDATE account SET balance = balance - 30 WHERE id = 3");
        int during = balance(3);
        Thread.sleep(5000);
        String before = String.valueOf(tx.getStatus());
        String cerr = "无";
        try {
            tx.commit();
        } catch (Exception e) {
            cerr = e.getClass().getSimpleName();
        }
        out("timeout", "超时 2 秒的全局事务，分支提交后（余额 " + during + "）睡 5 秒：提交前协调器上的状态 " + before + "；调用 commit() 的异常 " + cerr
                + "，之后状态 " + tx.getStatus() + "，余额 " + balance(3));
        RootContext.unbind();
        System.exit(0);
    }
}
