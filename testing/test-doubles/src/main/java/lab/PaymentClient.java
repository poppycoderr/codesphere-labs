package lab;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 调用支付服务查询状态；按录制时的响应格式解析 "status" 字段 */
public final class PaymentClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    public PaymentClient(String base) { this.base = base; }
    public String status(String paymentId) throws Exception {
        String body = http.send(HttpRequest.newBuilder(URI.create(base + "/payments/" + paymentId)).build(), HttpResponse.BodyHandlers.ofString()).body();
        return parse(body);
    }
    public static String parse(String body) {
        Matcher m = Pattern.compile("\"status\"\\s*:\\s*\"(\\w+)\"").matcher(body);
        return m.find() ? m.group(1) : "UNKNOWN";
    }
}
