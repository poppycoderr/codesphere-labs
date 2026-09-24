package labs.ddd.layered.domain;

/** 业务规则拒绝了命令。 */
public final class RegistrationRefused extends RuntimeException {
    public RegistrationRefused(String reason) {
        super(reason);
    }
}
