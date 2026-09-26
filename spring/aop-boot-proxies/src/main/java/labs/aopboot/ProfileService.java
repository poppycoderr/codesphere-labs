package labs.aopboot;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 同一个方法上同时有缓存与事务；另有自调用 @Async 与 @Cacheable 的入口。 */
@Service
public class ProfileService {
    private final JdbcTemplate jdbc;
    private final Auditor auditor;
    private final AtomicInteger loads = new AtomicInteger();
    private volatile String asyncThread;

    public ProfileService(JdbcTemplate jdbc, Auditor auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    /** 读取昵称：命中缓存时是否还开事务，取决于两个切面谁在外层。 */
    @Transactional(readOnly = true)
    @Cacheable("names")
    public String name(long id) {
        loads.incrementAndGet();
        return jdbc.queryForObject("SELECT name FROM profiles WHERE id = ?", String.class, id);
    }

    /** 不存在就创建一个默认资料；审计失败会把事务标记为只能回滚，但方法本身正常返回。 */
    @Transactional
    @Cacheable("created")
    public String createIfAbsent(long id) {
        loads.incrementAndGet();
        jdbc.update("INSERT INTO profiles VALUES (?, ?)", id, "new-" + id);
        try {
            auditor.record(id);
        } catch (RuntimeException e) {
            // 审计失败可以忽略——但共享事务已经被标记为 rollback-only
        }
        return "new-" + id;
    }

    public String nameTwiceViaThis(long id) {
        name(id);
        return name(id);
    }

    @Async
    public void work() {
        asyncThread = Thread.currentThread().getName();
    }

    public void workViaThis() {
        work();
    }

    public int loads() {
        return loads.get();
    }

    public String asyncThread() {
        return asyncThread;
    }
}
