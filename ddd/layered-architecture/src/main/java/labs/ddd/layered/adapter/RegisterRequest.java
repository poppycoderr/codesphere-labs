package labs.ddd.layered.adapter;

/** HTTP 请求体：字段都是字符串，格式由外部决定。 */
public record RegisterRequest(
        String sessionId,
        String attendeeId,
        String phone) {
}
