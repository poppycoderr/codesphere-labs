package labs.ddd.violations.bypass.domain;

/** 违规夹具：仓储端口。 */
public interface SessionRepository {
    int countConfirmed(String sessionId);
}
