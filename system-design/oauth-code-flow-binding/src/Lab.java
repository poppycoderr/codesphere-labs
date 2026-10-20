import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** 授权码、PKCE 与 state 各自防什么：在本地授权服务器上走完整的授权码流程，再逐项发送错误的请求 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class Lab {
    static final String WEB_CB = "https://web.example/callback", SPA_CB = "https://spa.example/callback";
    static final PasswordEncoder ENC = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Bean UserDetailsService users() {
        return new InMemoryUserDetailsManager(User.withUsername("alice").password(ENC.encode("example_password")).roles("USER").build());
    }
    @Bean PasswordEncoder passwordEncoder() { return ENC; }

    @Bean RegisteredClientRepository clients() {
        RegisteredClient web = RegisteredClient.withId("web").clientId("web").clientSecret(ENC.encode("example_password"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(WEB_CB).scope("openid").scope("orders.read")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build()).build();
        RegisteredClient rotating = RegisteredClient.from(web).id("web-rotate").clientId("web-rotate").clientSecret(ENC.encode("example_password"))
                .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build()).build();
        RegisteredClient spa = RegisteredClient.withId("spa").clientId("spa")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(SPA_CB).scope("orders.read")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).requireProofKey(true).build()).build();
        return new InMemoryRegisteredClientRepository(web, rotating, spa);
    }

    static String base;
    static HttpClient http;
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static HttpResponse<String> get(String url) throws Exception {
        // 像浏览器一样声明接受 HTML：未登录时得到登录页重定向，而不是 401
        return http.send(HttpRequest.newBuilder(URI.create(url.startsWith("http") ? url : base + url)).header("Accept", "text/html").build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(String path, String form, String basicUser) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (basicUser != null) b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicUser + ":example_password").getBytes()));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static String json(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\"([^\"]*)\"|true|false)").matcher(body);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
    }
    static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    /** 登录一次，之后的授权请求都带着同一个会话 */
    static void login() throws Exception {
        HttpResponse<String> page = get("/login");
        Matcher m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        if (!m.find()) throw new IllegalStateException("登录页没有 CSRF 令牌");
        HttpResponse<String> r = post("/login", "username=alice&password=example_password&_csrf=" + enc(m.group(1)), null);
        if (r.statusCode() != 302) throw new IllegalStateException("登录失败：" + r.statusCode());
    }
    /** 发起授权请求；返回最终的状态码与 Location（不跟随重定向） */
    static HttpResponse<String> authorize(String query) throws Exception { return get("/oauth2/authorize?" + query); }

    static String describe(HttpResponse<String> r) {
        String loc = r.headers().firstValue("Location").orElse(null);
        if (loc != null) {
            String err = param(loc, "error");
            String desc = param(loc, "error_description");
            return "302 → " + loc.replaceAll("\\?.*", "") + (err != null ? "，error=" + err + (desc != null ? "（" + desc + "）" : "") : param(loc, "code") != null ? "，带 code" + (param(loc, "state") != null ? "，state=" + param(loc, "state") : "") : "");
        }
        return r.statusCode() + (json(r.body(), "error") != null ? "，error=" + json(r.body(), "error") : "");
    }
    static String verifier() { byte[] b = new byte[32]; new Random(7).nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String challenge(String v) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII)));
    }
    static String code(HttpResponse<String> r) { return param(r.headers().firstValue("Location").orElseThrow(), "code"); }
    static String tokenResult(HttpResponse<String> r) {
        return r.statusCode() == 200 ? "200，access_token " + (json(r.body(), "access_token") != null ? "有" : "无") + "，refresh_token " + (json(r.body(), "refresh_token") != null ? "有" : "无") + "，id_token " + (json(r.body(), "id_token") != null ? "有" : "无")
                : r.statusCode() + "，error=" + json(r.body(), "error");
    }

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplication(Lab.class).run("--server.port=0", "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off", "--logging.level.root=OFF");
        try { run(ctx); } catch (Throwable t) { out("crash", t.toString()); } finally { ctx.close(); System.exit(0); }
    }

    static void run(ConfigurableApplicationContext ctx) throws Exception {
        base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
        http = HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        out("env", "spring-boot=" + org.springframework.boot.SpringBootVersion.getVersion() + " spring-security=" + org.springframework.security.core.SpringSecurityCoreVersion.getVersion() + " java=" + Runtime.version());

        String v = verifier();
        String webNoPkce = "response_type=code&client_id=web&redirect_uri=" + enc(WEB_CB) + "&scope=" + enc("openid orders.read") + "&state=s-123";
        String webAuth = webNoPkce + "&code_challenge=" + challenge(v) + "&code_challenge_method=S256";
        String cv = "&code_verifier=" + v;
        out("unauthenticated", "未登录时发起授权请求：" + describe(authorize(webAuth)).replace(base, ""));
        login();
        out("confidential.no_pkce", "机密客户端（有 client_secret）不带 code_challenge，默认设置：" + describe(authorize(webNoPkce)));
        HttpResponse<String> ok = authorize(webAuth);
        out("authorize", "登录后发起授权请求：" + describe(ok));
        String code = code(ok);
        HttpResponse<String> t1 = post("/oauth2/token", "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(WEB_CB) + cv, "web");
        out("token", "用授权码换令牌：" + tokenResult(t1));
        String access = json(t1.body(), "access_token"), refresh = json(t1.body(), "refresh_token"), idToken = json(t1.body(), "id_token");
        out("introspect.before", "内省第一次拿到的访问令牌：active=" + json(post("/oauth2/introspect", "token=" + enc(access), "web").body(), "active"));
        out("code.reuse", "同一个授权码再换一次：" + tokenResult(post("/oauth2/token", "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(WEB_CB) + cv, "web")));
        out("refresh.after_code_reuse", "同一次授权签发的 refresh_token 再用来刷新：" + tokenResult(post("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(refresh), "web")));
        out("introspect.after_reuse", "授权码被重复使用之后，再内省第一次的访问令牌：active=" + json(post("/oauth2/introspect", "token=" + enc(access), "web").body(), "active"));

        out("redirect.unregistered", "授权请求的 redirect_uri 未登记：" + describe(authorize("response_type=code&client_id=web&redirect_uri=" + enc("https://evil.example/cb") + "&scope=orders.read&state=s")));
        out("redirect.prefix", "redirect_uri 只比登记的多一段路径：" + describe(authorize("response_type=code&client_id=web&redirect_uri=" + enc(WEB_CB + "/extra") + "&scope=orders.read&state=s")));
        String code2 = code(authorize(webAuth));
        out("redirect.token_mismatch", "换令牌时的 redirect_uri 与授权请求不同：" + tokenResult(post("/oauth2/token", "grant_type=authorization_code&code=" + enc(code2) + "&redirect_uri=" + enc("https://web.example/other") + cv, "web")));
        String code3 = code(authorize(webAuth));
        out("client.mismatch", "web 拿到的授权码由 web-rotate 去换：" + tokenResult(post("/oauth2/token", "grant_type=authorization_code&code=" + enc(code3) + "&redirect_uri=" + enc(WEB_CB) + cv, "web-rotate")));
        out("state.echo", "state 由客户端生成，授权服务器原样带回（本次 state=s-123）；校验它是客户端的责任");

        String spaBase = "response_type=code&client_id=spa&redirect_uri=" + enc(SPA_CB) + "&scope=orders.read&state=p1";
        out("pkce.missing", "公共客户端不带 code_challenge：" + describe(authorize(spaBase)));
        String spaWithPkce = spaBase + "&code_challenge=" + challenge(v) + "&code_challenge_method=S256";
        String c4 = code(authorize(spaWithPkce));
        out("pkce.wrong", "带 S256 挑战，换令牌时给错误的 verifier：" + tokenResult(post("/oauth2/token", "grant_type=authorization_code&client_id=spa&code=" + enc(c4) + "&redirect_uri=" + enc(SPA_CB) + "&code_verifier=" + "x".repeat(43), null)));
        String c5 = code(authorize(spaWithPkce));
        out("pkce.missing_verifier", "带挑战，换令牌时不给 verifier：" + tokenResult(post("/oauth2/token", "grant_type=authorization_code&client_id=spa&code=" + enc(c5) + "&redirect_uri=" + enc(SPA_CB), null)));
        String c6 = code(authorize(spaWithPkce));
        out("pkce.ok", "给正确的 verifier（公共客户端）：" + tokenResult(post("/oauth2/token", "grant_type=authorization_code&client_id=spa&code=" + enc(c6) + "&redirect_uri=" + enc(SPA_CB) + "&code_verifier=" + v, null)));
        out("pkce.plain", "code_challenge_method=plain：" + describe(authorize(spaBase + "&code_challenge=" + v + "&code_challenge_method=plain")));

        out("refresh.default_setting", "TokenSettings 默认值：reuseRefreshTokens=" + TokenSettings.builder().build().isReuseRefreshTokens());
        String cr = code(authorize(webAuth));
        refresh = json(post("/oauth2/token", "grant_type=authorization_code&code=" + enc(cr) + "&redirect_uri=" + enc(WEB_CB) + cv, "web").body(), "refresh_token");
        HttpResponse<String> r1 = post("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(refresh), "web");
        String newRefresh = json(r1.body(), "refresh_token");
        out("refresh.first", "web 用 refresh_token 刷新：" + tokenResult(r1) + "；新的 refresh_token 与原来的" + (refresh.equals(newRefresh) ? "相同" : "不同"));
        out("refresh.again", "同一个 refresh_token 再刷新一次：" + tokenResult(post("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(refresh), "web")));
        String rc = code(authorize(webAuth.replace("client_id=web", "client_id=web-rotate")));
        String rRefresh = json(post("/oauth2/token", "grant_type=authorization_code&code=" + enc(rc) + "&redirect_uri=" + enc(WEB_CB) + cv, "web-rotate").body(), "refresh_token");
        HttpResponse<String> r2 = post("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(rRefresh), "web-rotate");
        String rNew = json(r2.body(), "refresh_token");
        out("refresh.explicit_false", "web-rotate 显式设 reuseRefreshTokens=false：刷新后新的 refresh_token 与原来的" + (rRefresh.equals(rNew) ? "相同" : "不同"));
        out("rotate.replay_old", "轮换之后再用原来的 refresh_token：" + tokenResult(post("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(rRefresh), "web-rotate")));
        out("rotate.after_replay", "旧的被重放之后，用轮换得到的新 refresh_token：" + tokenResult(post("/oauth2/token", "grant_type=refresh_token&refresh_token=" + enc(rNew), "web-rotate")));

        String fc = code(authorize(webAuth));
        String fresh = json(post("/oauth2/token", "grant_type=authorization_code&code=" + enc(fc) + "&redirect_uri=" + enc(WEB_CB) + cv, "web").body(), "access_token");
        NimbusJwtDecoder local = NimbusJwtDecoder.withJwkSetUri(base + "/oauth2/jwks").build();
        post("/oauth2/revoke", "token=" + enc(fresh) + "&token_type_hint=access_token", "web");
        String localResult; try { local.decode(fresh); localResult = "接受"; } catch (JwtException e) { localResult = "拒绝"; }
        out("revoke", "撤销一个访问令牌之后：内省 active=" + json(post("/oauth2/introspect", "token=" + enc(fresh), "web").body(), "active") + "；资源服务器用 JWK 本地验签：" + localResult);
        String idResult; try { idResult = "接受（aud=" + local.decode(idToken).getAudience() + "）"; } catch (JwtException e) { idResult = "拒绝"; }
        out("id_token_as_bearer", "把 ID Token 当作访问令牌交给只验签的资源服务器解码器：" + idResult);
    }
}
