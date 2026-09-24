package labs.ddd.violations.domainleak.domain;

import labs.ddd.violations.domainleak.infrastructure.SessionPO;

/** 违规夹具：领域对象直接持有持久化对象，想「省一次转换」。 */
public class Session {
    private SessionPO state;

    public int capacity() {
        return state.capacity;
    }
}
