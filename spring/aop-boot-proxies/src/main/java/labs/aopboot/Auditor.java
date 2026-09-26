package labs.aopboot;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 参与同一个事务的审计，总是失败。 */
@Service
public class Auditor {
    @Transactional
    public void record(long id) {
        throw new IllegalStateException("audit store unavailable");
    }
}
