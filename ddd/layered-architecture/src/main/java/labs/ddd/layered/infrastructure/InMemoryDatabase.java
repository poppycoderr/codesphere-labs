package labs.ddd.layered.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import labs.ddd.layered.application.Transactions;
import labs.ddd.layered.observability.Trace;

/** 带事务的内存表：写入先进入当前事务的暂存区，提交时生效，回滚时丢弃。单线程使用。 */
public final class InMemoryDatabase implements Transactions {

    private final Map<String, SessionRecord> committed = new HashMap<>();
    private Map<String, SessionRecord> staged;
    private List<Runnable> afterCommit;
    private Runnable interleaved;

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        staged = new HashMap<>();
        afterCommit = new ArrayList<>();
        Trace.step("infrastructure", "BEGIN");
        try {
            T result = work.get();
            committed.putAll(staged);
            Trace.step("infrastructure", "COMMIT");
            List<Runnable> actions = afterCommit;
            staged = null;
            afterCommit = null;
            actions.forEach(Runnable::run);
            return result;
        } catch (RuntimeException e) {
            staged = null;
            afterCommit = null;
            Trace.step("infrastructure", "ROLLBACK（" + e.getClass().getSimpleName() + "）");
            throw e;
        }
    }

    @Override
    public void afterCommit(Runnable action) {
        afterCommit.add(action);
    }

    SessionRecord read(String id) {
        return staged != null && staged.containsKey(id) ? staged.get(id) : committed.get(id);
    }

    void write(SessionRecord record) {
        staged.put(record.id(), record);
    }

    /** 当前行已提交的版本。测试可以在这之前插入另一个请求的提交。 */
    long committedVersion(String id) {
        if (interleaved != null) {
            Runnable r = interleaved;
            interleaved = null;
            r.run();
        }
        return committed.get(id).version();
    }

    /** 模拟另一个请求在本次加载之后、保存之前提交了对同一行的修改。 */
    public void interleaveCommitBeforeNextSave(String id) {
        interleaved = () -> {
            SessionRecord r = committed.get(id);
            committed.put(id, new SessionRecord(r.id(), r.capacity(), r.version() + 1, r.confirmed(), r.waitlist()));
        };
    }

    public void seed(SessionRecord record) {
        committed.put(record.id(), record);
    }

    public SessionRecord committed(String id) {
        return committed.get(id);
    }
}
