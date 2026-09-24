package labs.creation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 按 id 保存报名的内存仓储：id 为空时分配新 id，否则按 id 覆盖，并做乐观锁检查。 */
public final class Repository {
    private final Map<Long, Registration> rows = new HashMap<>();
    private final Map<Long, Long> versions = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    public Registration save(Registration r) {
        if (r.id == null) {
            r.id = ids.incrementAndGet();
        } else if (versions.containsKey(r.id) && versions.get(r.id) != r.version) {
            throw new IllegalStateException("version conflict for id " + r.id);
        }
        r.version++;
        versions.put(r.id, r.version);
        rows.put(r.id, r);
        return r;
    }

    public Registration find(long id) {
        return rows.get(id);
    }

    public int size() {
        return rows.size();
    }
}
