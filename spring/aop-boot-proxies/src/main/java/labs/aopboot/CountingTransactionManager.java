package labs.aopboot;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** 记录真正开启了多少个物理事务。 */
public class CountingTransactionManager extends JdbcTransactionManager {
    public final AtomicInteger begins = new AtomicInteger();

    public CountingTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        begins.incrementAndGet();
        super.doBegin(transaction, definition);
    }
}
