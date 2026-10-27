package labs.txattr;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只读与超时两个事务属性的几种用法。 */
@Service
public class TxAttrService {
    private final JdbcTemplate jdbc;
    private final ItemRepository items;
    private final EntityManager em;

    TxAttrService(JdbcTemplate jdbc, ItemRepository items, EntityManager em) {
        this.jdbc = jdbc;
        this.items = items;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public void insertInReadOnly(long id) {
        jdbc.update("INSERT INTO item(id, name) VALUES (?, 'jdbc')", id);
    }

    @Transactional(readOnly = true)
    public void renameManagedEntityInReadOnly(long id) {
        items.findById(id).orElseThrow().rename("改过的名字");
    }

    @Transactional(readOnly = true)
    public void explicitSaveInReadOnly(long id) {
        Item item = items.findById(id).orElseThrow();
        item.rename("显式 save");
        items.save(item);
    }

    @Transactional
    public void renameManagedEntity(long id) {
        items.findById(id).orElseThrow().rename("读写事务改的名字");
    }

    /** 最后一条语句之后才耗时。 */
    @Transactional(timeout = 1)
    public void workAfterLastStatement(long id) throws InterruptedException {
        jdbc.update("INSERT INTO item(id, name) VALUES (?, 'slow-after')", id);
        Thread.sleep(1500);
    }

    /** 先耗时，再执行语句。 */
    @Transactional(timeout = 1)
    public void workBeforeStatement(long id) throws InterruptedException {
        Thread.sleep(1500);
        jdbc.update("INSERT INTO item(id, name) VALUES (?, 'slow-before')", id);
    }

    /** 单条语句本身超过剩余时间。 */
    @Transactional(timeout = 1)
    public void longStatement() {
        jdbc.queryForObject("SELECT SLEEP(3)", Integer.class);
    }
}
