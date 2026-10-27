package labs.routing;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** 两个 H2 库（主库 / 副本），各有一行标明自己是谁；路由数据源在取连接时决定去哪个库。 */
@SpringBootApplication
public class RoutingApp {

    static DataSource h2(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate t = new JdbcTemplate(ds);
        t.execute("CREATE TABLE IF NOT EXISTS whoami(name VARCHAR(16))");
        t.update("DELETE FROM whoami");
        t.update("INSERT INTO whoami VALUES (?)", name);
        return ds;
    }

    @Bean
    @Primary
    DataSource routing() {
        RoutingDataSource r = new RoutingDataSource();
        r.setTargetDataSources(Map.of("primary", h2("primary"), "replica", h2("replica")));
        r.setDefaultTargetDataSource(h2("primary"));
        r.afterPropertiesSet();
        return r;
    }

    @Bean
    DataSource lazyRouting(DataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource routing) {
        return new DataSourceTransactionManager(routing);
    }

    @Bean
    TransactionTemplate tx(DataSourceTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    @Bean
    TransactionTemplate lazyTx(DataSource lazyRouting) {
        return new TransactionTemplate(new DataSourceTransactionManager(lazyRouting));
    }
}
