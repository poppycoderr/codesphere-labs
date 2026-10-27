package labs.routing;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class RoutingTest {
    @Autowired
    DataSource routing;
    @Autowired
    @Qualifier("lazyRouting")
    DataSource lazyRouting;
    @Autowired
    @Qualifier("tx")
    TransactionTemplate tx;
    @Autowired
    @Qualifier("lazyTx")
    TransactionTemplate lazyTx;

    JdbcTemplate jdbc() {
        return new JdbcTemplate(routing);
    }

    String who(DataSource ds) {
        return new JdbcTemplate(ds).queryForObject("SELECT name FROM whoami", String.class);
    }

    @AfterEach
    void clear() {
        RoutingDataSource.KEY.remove();
        RoutingDataSource.routeReadOnlyToReplica = false;
    }

    @Test
    void keyInsideTransaction() {
        RoutingDataSource.KEY.set("replica");
        String outside = who(routing);
        RoutingDataSource.KEY.remove();
        String inside = tx.execute(s -> {
            RoutingDataSource.KEY.set("replica"); // 事务已经开始，连接已经拿到
            return who(routing);
        });
        RoutingDataSource.KEY.remove();
        RoutingDataSource.KEY.set("replica");
        String before = tx.execute(s -> who(routing));
        Facts.record("key.timing", "设置路由键为 replica：在事务外查询落到 " + outside + "；在事务方法内部设置后查询落到 " + inside + "；在进入事务之前设置落到 " + before);
    }

    @Test
    void lazyConnection() {
        String inside = lazyTx.execute(s -> {
            RoutingDataSource.KEY.set("replica");
            return who(lazyRouting);
        });
        Facts.record("key.lazy", "外面包一层 LazyConnectionDataSourceProxy，同样在事务内部、第一条语句之前设置：落到 " + inside + "（连接推迟到第一条语句才取）");
    }

    @Test
    void readOnlyRouting() {
        RoutingDataSource.routeReadOnlyToReplica = true;
        tx.setReadOnly(true);
        String eager = tx.execute(s -> who(routing));
        tx.setReadOnly(false);
        lazyTx.setReadOnly(true);
        String lazy = lazyTx.execute(s -> who(lazyRouting));
        lazyTx.setReadOnly(false);
        Facts.record("readonly.route", "按 isCurrentTransactionReadOnly() 路由只读事务：直接用路由数据源落到 " + eager + "（取连接时只读标志还没设上）；包一层 LazyConnectionDataSourceProxy 落到 " + lazy);
    }

    @Test
    void nestedScopeClears() {
        RoutingDataSource.KEY.set("replica");
        String first = who(routing);
        // 被调用的工具方法临时切到主库，结束时 remove
        RoutingDataSource.KEY.set("primary");
        who(routing);
        RoutingDataSource.KEY.remove();
        String after = who(routing);
        RoutingDataSource.KEY.set("replica");
        String prev = RoutingDataSource.KEY.get();
        RoutingDataSource.KEY.set("primary");
        who(routing);
        RoutingDataSource.KEY.set(prev);
        String restored = who(routing);
        Facts.record("key.nested", "外层设为 replica（第一次查询落到 " + first + "），中途调用的方法临时切到 primary 后 remove：外层下一次查询落到 " + after + "；改成保存并恢复：落到 " + restored);
    }

    @Test
    void bucketMask() {
        int buckets = 6;
        java.util.TreeSet<Integer> used = new java.util.TreeSet<>();
        for (int i = 0; i < 10_000; i++) {
            used.add(Integer.hashCode(i * 2654435761L > 0 ? (int) (i * 2654435761L) : i) & (buckets - 1));
        }
        java.util.TreeSet<Integer> used8 = new java.util.TreeSet<>();
        for (int i = 0; i < 10_000; i++) {
            used8.add((int) (i * 2654435761L) & (8 - 1));
        }
        Facts.record("bucket.mask", "用 hash & (桶数 − 1) 取桶：6 个桶时只用到了 " + used + "，8 个桶时用到了 " + used8 + "（位与取模要求桶数是 2 的幂）");
    }
}
