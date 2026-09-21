package labs.aop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 记录事务的开启、提交、回滚，以及业务方法执行时是否处在事务中。 */
public final class Recorder {
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private Recorder() {}

    public static void reset() { EVENTS.clear(); }

    /** 业务方法调用：记录「方法名:tx」或「方法名:no-tx」 */
    public static void mark(String method) {
        String state = TransactionSynchronizationManager.isActualTransactionActive() ? "tx" : "no-tx";
        EVENTS.add(method + ":" + state);
        System.out.println("    " + method + "()：" + (state.equals("tx") ? "在事务中" : "没有事务"));
    }

    /** 不连接数据库的事务管理器，只记录事务边界 */
    public static final class TxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition def) { EVENTS.add("begin"); System.out.println("      [tx] begin " + def.getName()); }
        @Override protected void doCommit(DefaultTransactionStatus s) { EVENTS.add("commit"); System.out.println("      [tx] commit"); }
        @Override protected void doRollback(DefaultTransactionStatus s) { EVENTS.add("rollback"); System.out.println("      [tx] rollback"); }
    }
}
