package labs.ddd.registration;

/** 命令被业务规则拒绝。拒绝不是领域事件：什么事实都没有发生。 */
public final class RegistrationRefused extends RuntimeException {

    public enum Reason { DUPLICATE, SESSION_CLOSED, TOO_LATE_TO_CANCEL, NOT_REGISTERED }

    private final Reason reason;

    public RegistrationRefused(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
