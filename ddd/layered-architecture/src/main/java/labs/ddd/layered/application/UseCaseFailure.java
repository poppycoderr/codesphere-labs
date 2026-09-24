package labs.ddd.layered.application;

/** 应用层对外的失败类型，适配器只认识它们，不认识领域异常。 */
public sealed class UseCaseFailure extends RuntimeException {

    UseCaseFailure(String message) {
        super(message);
    }

    public static final class InvalidInput extends UseCaseFailure {
        InvalidInput(String message) {
            super(message);
        }
    }

    public static final class Rejected extends UseCaseFailure {
        Rejected(String reason) {
            super(reason);
        }
    }

    public static final class Conflict extends UseCaseFailure {
        Conflict() {
            super("请重试");
        }
    }
}
