package labs.ddd.violations.bypass.adapter;

import labs.ddd.violations.bypass.domain.SessionRepository;

/** 违规夹具：控制器跳过应用层直接调用仓储，「只是查个数」。 */
public class SessionController {
    private final SessionRepository repository;

    public SessionController(SessionRepository repository) {
        this.repository = repository;
    }

    public int remaining(String sessionId) {
        return 100 - repository.countConfirmed(sessionId);
    }
}
