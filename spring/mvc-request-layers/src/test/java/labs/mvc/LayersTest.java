package labs.mvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LayersTest {
    @LocalServerPort
    int port;
    @Autowired
    Journal journal;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        journal.steps.clear();
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String headers(HttpResponse<String> r) {
        StringBuilder sb = new StringBuilder();
        for (String h : new String[] {"X-Filter-Before", "X-Pre", "X-Post", "X-Filter-After"}) {
            sb.append(sb.isEmpty() ? "" : "，").append(h).append(r.headers().firstValue(h).isPresent() ? " 有" : " 无");
        }
        return sb.toString();
    }

    @Test
    void responseBody() throws Exception {
        var r = get("/json");
        Facts.record("body.json", "@ResponseBody 返回对象：状态 " + r.statusCode() + "；响应头 " + headers(r) + "；经过 " + String.join(" → ", journal.steps));
    }

    @Test
    void responseEntity() throws Exception {
        var r = get("/entity");
        Facts.record("body.entity", "ResponseEntity 返回字符串：状态 " + r.statusCode() + "；响应头 " + headers(r) + "；经过 " + String.join(" → ", journal.steps));
    }

    @Test
    void handlerThrows() throws Exception {
        var r = get("/fail");
        Facts.record("handler.throws", "处理器抛异常、@RestControllerAdvice 处理：状态 " + r.statusCode() + "，响应体 " + r.body() + "；经过 " + String.join(" → ", journal.steps));
    }

    @Test
    void preHandleFalse() throws Exception {
        var r = get("/blocked");
        Facts.record("prehandle.false", "preHandle 返回 false：状态 " + r.statusCode() + "，响应体长度 " + r.body().length() + "；经过 " + String.join(" → ", journal.steps));
    }

    @Test
    void filterThrows() throws Exception {
        var r = get("/filter-fail");
        Facts.record("filter.throws", "Filter 里抛异常：状态 " + r.statusCode() + "，响应体是否来自 @RestControllerAdvice=" + r.body().contains("过滤器里的检查失败")
                + "；经过 " + String.join(" → ", journal.steps));
    }
}
