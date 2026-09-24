package labs.ddd.boundaries.notification.api;

/** 报名上下文依赖的通知能力。同进程调用与 HTTP 调用是它的两个实现。 */
public interface NotificationPort {

    void confirmationSent(ConfirmationNotice notice, String traceId);
}
