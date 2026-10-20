import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.security.oauth2.jwt.*;

/** 访问令牌验签之后还要校验什么：默认解码器检查了哪些声明，没有检查哪些；受众、签发者、类型、算法与时钟偏差 */
public class Lab {
    static final Instant NOW = Instant.now();
    static RSAKey key, otherKey;

    static String rs256(JOSEObjectType typ, Consumer<JWTClaimsSet.Builder> claims, RSAKey signWith) throws Exception {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject("user-1").issuer("https://idp.example").audience("orders-api")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(300))).claim("scope", "orders.read");
        claims.accept(b);
        JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signWith.getKeyID());
        if (typ != null) h.type(typ);
        SignedJWT jwt = new SignedJWT(h.build(), b.build());
        jwt.sign(new RSASSASigner(signWith));
        return jwt.serialize();
    }
    /** 同一组声明签两份：typ=JWT 给默认解码器，typ=at+jwt 给按 RFC 9068 配置的解码器 */
    static String[] token(Consumer<JWTClaimsSet.Builder> claims) throws Exception {
        return new String[]{rs256(JOSEObjectType.JWT, claims, key), rs256(new JOSEObjectType("at+jwt"), claims, key)};
    }

    static String decode(JwtDecoder d, String token) {
        try {
            Jwt j = d.decode(token);
            return "接受（aud=" + j.getAudience() + "，iss=" + j.getIssuer() + "）";
        } catch (JwtException e) {
            String m = e.getMessage().replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<时间>");
            return "拒绝：" + (m.length() > 110 ? m.substring(0, 110) + "…" : m);
        }
    }
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    public static void main(String[] args) throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        out("env", "spring-security=" + org.springframework.security.core.SpringSecurityCoreVersion.getVersion() + " nimbus-jose-jwt=10.9.1 java=" + Runtime.version());

        NimbusJwtDecoder byDefault = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        NimbusJwtDecoder strict = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        strict.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator("https://idp.example"), new JwtAudienceValidator("orders-api"), new JwtTypeValidator("at+jwt")));

        out("default.at_jwt", "typ=at+jwt、其余全部正确的访问令牌：默认解码器 " + decode(byDefault, token(b -> {})[1]));

        Map<String, String[]> tokens = new LinkedHashMap<>();
        tokens.put("ok", token(b -> {}));
        tokens.put("aud_other", token(b -> b.audience("billing-api")));
        tokens.put("iss_other", token(b -> b.issuer("https://other-idp.example")));
        tokens.put("id_token", token(b -> b.audience("web-app").claim("nonce", "n-1").claim("scope", null)));
        tokens.put("expired_30s", token(b -> b.issueTime(Date.from(NOW.minusSeconds(600))).expirationTime(Date.from(NOW.minusSeconds(30)))));
        tokens.put("expired_5m", token(b -> b.issueTime(Date.from(NOW.minusSeconds(600))).expirationTime(Date.from(NOW.minusSeconds(300)))));
        tokens.put("no_exp", token(b -> b.expirationTime(null)));
        tokens.put("other_key", new String[]{rs256(JOSEObjectType.JWT, b -> {}, otherKey), rs256(new JOSEObjectType("at+jwt"), b -> {}, otherKey)});
        String none = new PlainJWT(new JWTClaimsSet.Builder().subject("user-1").issuer("https://idp.example").audience("orders-api").expirationTime(Date.from(NOW.plusSeconds(300))).build()).serialize();
        tokens.put("alg_none", new String[]{none, none});
        String[] hsTokens = new String[2];
        for (int i = 0; i < 2; i++) {
            SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(i == 0 ? JOSEObjectType.JWT : new JOSEObjectType("at+jwt")).build(),
                    new JWTClaimsSet.Builder().subject("user-1").issuer("https://idp.example").audience("orders-api").expirationTime(Date.from(NOW.plusSeconds(300))).build());
            hs.sign(new MACSigner(key.toRSAPublicKey().getEncoded()));
            hsTokens[i] = hs.serialize();
        }
        tokens.put("hs256_pubkey", hsTokens);

        Map<String, String> label = Map.ofEntries(
                Map.entry("ok", "签发者、受众正确"), Map.entry("aud_other", "受众是 billing-api"), Map.entry("iss_other", "签发者是另一个身份服务（同一把密钥）"),
                Map.entry("id_token", "一个 ID Token（受众是网页应用、带 nonce）"),
                Map.entry("expired_30s", "30 秒前过期"), Map.entry("expired_5m", "5 分钟前过期"), Map.entry("no_exp", "没有 exp"),
                Map.entry("other_key", "用另一把同 kid 的密钥签名"), Map.entry("alg_none", "alg=none 的未签名令牌"), Map.entry("hs256_pubkey", "HS256，用公钥字节当密钥签名"));
        for (var e : tokens.entrySet()) {
            out("default." + e.getKey(), label.get(e.getKey()) + "：默认解码器（typ=JWT）" + decode(byDefault, e.getValue()[0]));
            out("strict." + e.getKey(), label.get(e.getKey()) + "：加上签发者、受众、at+jwt 类型校验 " + decode(strict, e.getValue()[1]));
        }

        JwtTimestampValidator requireExp = new JwtTimestampValidator();
        requireExp.setAllowEmptyExpiryClaim(false);
        NimbusJwtDecoder strictExp = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        strictExp.setJwtValidator(JwtValidators.createDefaultWithValidators(new JwtIssuerValidator("https://idp.example"),
                new JwtAudienceValidator("orders-api"), new JwtTypeValidator("at+jwt"), requireExp));
        out("strict.no_exp.required", "没有 exp：再加上 setAllowEmptyExpiryClaim(false) 的时间校验器 " + decode(strictExp, tokens.get("no_exp")[1]));

        // 令牌里的 scope 是空格分隔的字符串，按子串判断是常见错误
        for (String scopes : List.of("orders.read orders.write", "profile orders.readonly", "billing.read orders.read")) {
            boolean naive = scopes.indexOf("orders.read") > 0;
            boolean exact = Set.of(scopes.split(" ")).contains("orders.read");
            out("scope." + scopes.replace(" ", "_"), "scope=\"" + scopes + "\"：indexOf(\"orders.read\") > 0 → " + naive + "；按空格拆成集合精确匹配 → " + exact);
        }
    }
}
