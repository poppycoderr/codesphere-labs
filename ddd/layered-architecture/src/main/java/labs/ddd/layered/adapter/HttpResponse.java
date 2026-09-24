package labs.ddd.layered.adapter;

/** 适配器返回的响应。 */
public record HttpResponse(
        int status,
        String body) {
}
