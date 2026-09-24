package labs.ddd.boundaries.notification.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知上下文对外发布的语言：报名上下文只认识这个类型，不认识模板、渠道和投递记录。
 * noticeId 由调用方生成，用于去重。
 */
public record ConfirmationNotice(
        String noticeId,
        String sessionId,
        String attendeeId) {

    public String encode() {
        return "noticeId=" + noticeId + "&sessionId=" + sessionId + "&attendeeId=" + attendeeId;
    }

    public static ConfirmationNotice decode(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(kv[0], kv[1]);
        }
        return new ConfirmationNotice(m.get("noticeId"), m.get("sessionId"), m.get("attendeeId"));
    }
}
