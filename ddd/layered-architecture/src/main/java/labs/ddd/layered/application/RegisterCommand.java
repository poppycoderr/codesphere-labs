package labs.ddd.layered.application;

/** 用例输入：表达意图，不携带 HTTP 细节；字段仍是外部给的原始值，由应用服务转换成领域类型。 */
public record RegisterCommand(
        String sessionId,
        String attendeeId,
        String phone) {
}
