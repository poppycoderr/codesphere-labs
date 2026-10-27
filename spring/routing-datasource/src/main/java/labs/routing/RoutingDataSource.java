package labs.routing;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 路由键来自线程局部变量；没有显式设置时，可选地按「当前事务是否只读」路由到副本。
 * determineCurrentLookupKey 只在取连接的那一刻被调用。
 */
public class RoutingDataSource extends AbstractRoutingDataSource {
    static final ThreadLocal<String> KEY = new ThreadLocal<>();
    static volatile boolean routeReadOnlyToReplica;

    @Override
    protected Object determineCurrentLookupKey() {
        String k = KEY.get();
        if (k != null) {
            return k;
        }
        if (routeReadOnlyToReplica && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return "replica";
        }
        return "primary";
    }
}
