import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

/** Jakarta REST 的默认行为：资源方法返回什么、抛什么，客户端实际收到什么；参数转换失败时的状态码与转换方式的选择 */
public class Lab {
    static final AtomicInteger getCalls = new AtomicInteger();

    public static class Conflict extends RuntimeException { Conflict(String m) { super(m); } }
    public static class Broken extends RuntimeException { Broken(String m) { super(m); } }
    public static class Plain { public final String v = "x"; }

    @Path("r")
    public static class Responses {
        @GET @Path("text") @Produces("text/plain") public String text() { getCalls.incrementAndGet(); return "hello"; }
        @GET @Path("null") @Produces("text/plain") public String nothing() { return null; }
        @POST @Path("void") public void accept() { }
        @GET @Path("explicit") public Response explicit() { return Response.status(201).header("X-Trace", "t1").entity("created").type("text/plain").build(); }
        @GET @Path("wae") public String wae() { throw new WebApplicationException(Response.status(409).entity("taken").type("text/plain").build()); }
        @GET @Path("mapped") public String mapped() { throw new Conflict("库存不足"); }
        @GET @Path("mapper-broken") public String mapperBroken() { throw new Broken("x"); }
        @GET @Path("unmapped") public String unmapped() { throw new IllegalStateException("未处理"); }
        @GET @Path("no-writer") public Plain noWriter() { return new Plain(); }
        @PUT @Path("text") public String put(String body) { return body; }
    }

    public static class ConflictMapper implements ExceptionMapper<Conflict> {
        public Response toResponse(Conflict e) { return Response.status(422).entity("mapped:" + e.getMessage()).type("text/plain").build(); }
    }
    /** 映射器自身抛异常 */
    public static class BrokenMapper implements ExceptionMapper<Broken> {
        public Response toResponse(Broken e) { throw new IllegalStateException("映射器坏了"); }
    }

    /** 同时有 String 构造器、valueOf 和 fromString 的类型 */
    public static class Code {
        final String how;
        public Code(String s) { how = "构造器(" + s + ")"; }
        private Code(String s, String how) { this.how = how + "(" + s + ")"; }
        public static Code valueOf(String s) { return new Code(s, "valueOf"); }
        public static Code fromString(String s) { return new Code(s, "fromString"); }
        public String toString() { return how; }
    }
    public static class OnlyStatics {
        final String how;
        private OnlyStatics(String how) { this.how = how; }
        public static OnlyStatics valueOf(String s) { return new OnlyStatics("valueOf(" + s + ")"); }
        public static OnlyStatics fromString(String s) { return new OnlyStatics("fromString(" + s + ")"); }
        public String toString() { return how; }
    }
    public enum Level3 { LOW, HIGH; public static Level3 fromString(String s) { return s.equalsIgnoreCase("h") ? HIGH : LOW; } }
    /** 转换时抛异常的类型 */
    public static class Strict { public Strict(String s) { if (s.isEmpty() || !s.chars().allMatch(Character::isDigit)) throw new IllegalArgumentException("不是数字：" + s); } }

    @Path("p")
    @Produces("text/plain")
    public static class Params {
        @GET @Path("int") public String i(@QueryParam("n") int n) { return "int=" + n; }
        @GET @Path("boxed") public String b(@QueryParam("n") Integer n) { return "Integer=" + n; }
        @GET @Path("default") public String d(@QueryParam("n") @DefaultValue("5") int n) { return "int=" + n; }
        @GET @Path("path/{n}") public String p(@PathParam("n") int n) { return "int=" + n; }
        @GET @Path("header") public String h(@HeaderParam("X-N") int n) { return "int=" + n; }
        @GET @Path("code") public String c(@QueryParam("c") Code c) { return String.valueOf(c); }
        @GET @Path("statics") public String s(@QueryParam("c") OnlyStatics c) { return String.valueOf(c); }
        @GET @Path("enum") public String e(@QueryParam("l") Level3 l) { return String.valueOf(l); }
        @GET @Path("strict-query") public String sq(@QueryParam("s") Strict s) { return "ok"; }
        @GET @Path("strict-header") public String sh(@HeaderParam("X-S") Strict s) { return "ok"; }
        @GET @Path("list") public String l(@QueryParam("x") List<Integer> xs) { return "list=" + xs; }
    }

    static HttpClient client = HttpClient.newHttpClient();
    static String base;

    static String call(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, method.equals("PUT") ? HttpRequest.BodyPublishers.ofString("b") : HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        StringBuilder s = new StringBuilder("HTTP " + r.statusCode());
        r.headers().firstValue("Allow").ifPresent(a -> s.append("，Allow: ").append(String.join(",", java.util.Arrays.stream(a.split(",")).map(String::trim).sorted().toList())));
        r.headers().firstValue("X-Trace").ifPresent(a -> s.append("，X-Trace: ").append(a));
        r.headers().firstValue("Content-Length").ifPresent(a -> s.append("，Content-Length: ").append(a));
        s.append("，body「").append(r.body().length() > 60 ? r.body().substring(0, 60) + "…" : r.body()).append("」");
        return s.toString();
    }
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    public static void main(String[] args) throws Exception {
        Logger.getLogger("org.glassfish").setLevel(Level.OFF);
        Logger.getLogger("").setLevel(Level.OFF);
        ResourceConfig rc = new ResourceConfig(Responses.class, Params.class, ConflictMapper.class, BrokenMapper.class);
        HttpServer server = JdkHttpServerFactory.createHttpServer(URI.create("http://127.0.0.1:0/"), rc, false);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        out("env", "jersey=" + org.glassfish.jersey.internal.Version.getVersion() + " java=" + Runtime.version());
        out("resp.string", "返回 String：" + call("GET", "r/text"));
        out("resp.null", "返回 null：" + call("GET", "r/null"));
        out("resp.void", "void 方法：" + call("POST", "r/void"));
        out("resp.explicit", "返回 Response.status(201)：" + call("GET", "r/explicit"));
        int before = getCalls.get();
        out("resp.head", "对只有 @GET 的资源发 HEAD：" + call("HEAD", "r/text") + "；GET 方法被调用 " + (getCalls.get() - before) + " 次");
        out("resp.options", "没写 @OPTIONS 时发 OPTIONS：" + call("OPTIONS", "r/text"));
        out("resp.options_missing", "对不存在的路径发 OPTIONS：" + call("OPTIONS", "r/nope"));
        out("resp.405", "对只有 GET/PUT 的资源发 DELETE：" + call("DELETE", "r/text"));
        out("resp.404", "不存在的路径：" + call("GET", "r/nope"));
        out("exc.wae", "抛 WebApplicationException(409)：" + call("GET", "r/wae"));
        out("exc.mapped", "抛有映射器的异常：" + call("GET", "r/mapped"));
        out("exc.mapper_broken", "映射器自身抛异常：" + call("GET", "r/mapper-broken"));
        out("exc.unmapped", "抛没有映射器的 RuntimeException：" + call("GET", "r/unmapped"));
        out("exc.no_writer", "返回没有 MessageBodyWriter 的类型：" + call("GET", "r/no-writer"));

        out("param.int_missing", "@QueryParam int 缺省：" + call("GET", "p/int"));
        out("param.boxed_missing", "@QueryParam Integer 缺省：" + call("GET", "p/boxed"));
        out("param.default", "@DefaultValue(\"5\") 缺省：" + call("GET", "p/default"));
        out("param.int_empty", "@QueryParam int 传空串 ?n=：" + call("GET", "p/int?n="));
        out("param.query_bad", "@QueryParam int 传 abc：" + call("GET", "p/int?n=abc"));
        out("param.path_bad", "@PathParam int 传 abc：" + call("GET", "p/path/abc"));
        out("param.header_bad", "@HeaderParam int 传 abc：" + call("GET", "p/header", "X-N", "abc"));
        out("param.code", "类型同时有 String 构造器、valueOf、fromString：" + call("GET", "p/code?c=7"));
        out("param.statics", "只有 valueOf 与 fromString：" + call("GET", "p/statics?c=7"));
        out("param.enum", "枚举（自带 valueOf，另写 fromString），传 h：" + call("GET", "p/enum?l=h"));
        out("param.strict_query", "@QueryParam 的转换构造器抛 IllegalArgumentException：" + call("GET", "p/strict-query?s=x1"));
        out("param.strict_header", "@HeaderParam 的转换构造器抛 IllegalArgumentException：" + call("GET", "p/strict-header", "X-S", "x1"));
        out("param.list", "@QueryParam List<Integer>，?x=1&x=2：" + call("GET", "p/list?x=1&x=2"));
        out("param.list_bad", "@QueryParam List<Integer>，?x=1&x=b：" + call("GET", "p/list?x=1&x=b"));
        server.stop(0);
        System.exit(0);
    }
}
