import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 证书链：本机启动 HTTPS 服务，分别配置「叶证书加中间证书」「只有叶证书」等几种密钥库，
 * 用只信任根证书的 JDK HttpClient 去访问，记录结果或握手异常；另外用不做校验的连接看服务端实际发来了几张证书。
 */
public class ChainLab {
    static final char[] PW = "example_password".toCharArray();
    static String dir;

    static KeyStore load(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(dir + "/" + name + ".p12")) { ks.load(in, PW); }
        return ks;
    }

    static HttpsServer server(String keystore) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(keystore), PW);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer s = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(ctx));
        s.createContext("/", ex -> { byte[] b = "ok".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        s.start();
        return s;
    }

    static SSLContext clientContext(String truststore) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load(truststore));
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** 不做任何校验，只为了看服务端发来的证书 */
    static String sent(int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", port)) {
            s.startHandshake();
            StringBuilder sb = new StringBuilder();
            var certs = s.getSession().getPeerCertificates();
            for (var c : certs) {
                X509Certificate x = (X509Certificate) c;
                sb.append(sb.length() == 0 ? "" : " → ").append(x.getSubjectX500Principal().getName()).append("（签发者 ").append(x.getIssuerX500Principal().getName()).append("）");
            }
            return certs.length + " 张：" + sb;
        }
    }

    static String call(int port, String truststore) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().sslContext(clientContext(truststore)).build()) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/")).build(), HttpResponse.BodyHandlers.ofString());
            return "HTTP " + r.statusCode();
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            return e.getClass().getSimpleName() + "：" + e.getMessage().replaceAll("localhost:\\d+", "localhost") + "（根因 " + root.getClass().getSimpleName() + "）";
        }
    }

    static void scenario(String key, String what, String keystore, String truststore) throws Exception {
        HttpsServer s = server(keystore);
        try {
            int port = s.getAddress().getPort();
            System.out.println(key + ".sent\t" + what + "，服务端发来 " + sent(port));
            System.out.println(key + ".client\t" + call(port, truststore));
        } finally { s.stop(0); }
    }

    public static void main(String[] args) throws Exception {
        dir = args[0];
        boolean aia = Boolean.getBoolean("com.sun.security.enableAIAcaIssuers");
        System.out.println("env\tjava.version=" + System.getProperty("java.version") + " enableAIAcaIssuers=" + aia);
        // 提供中间证书下载的 HTTP 服务，对应叶证书里 AIA 扩展写的地址
        HttpServer issuer = HttpServer.create(new InetSocketAddress("localhost", 18080), 0);
        int[] downloads = new int[1];
        issuer.createContext("/inter.der", ex -> { downloads[0]++; byte[] b = Files.readAllBytes(Path.of(dir, "inter.der")); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        issuer.start();
        try {
            if (!aia) {
                scenario("full_chain", "密钥库里是叶证书加中间证书，客户端只信任根", "server-full", "trust-root");
                scenario("leaf_only", "密钥库里只有叶证书，客户端只信任根", "server-leaf-only", "trust-root");
                scenario("leaf_only.inter_trusted", "密钥库里只有叶证书，客户端的信任库里另有中间证书", "server-leaf-only", "trust-root-and-inter");
                scenario("wrong_root", "完整的链，客户端信任的是另一个根", "server-full", "trust-other");
                scenario("wrong_host", "完整的链，证书里的域名是 other.example", "server-wrong-host", "trust-root");
                scenario("expired", "完整的链，叶证书已过期", "server-expired", "trust-root");
            }
            scenario("aia_leaf_only", "只有叶证书，叶证书带 AIA 扩展指向中间证书的下载地址，客户端只信任根", "server-aia-leaf-only", "trust-root");
            System.out.println("aia.downloads\t中间证书被下载 " + downloads[0] + " 次");
        } finally { issuer.stop(0); }
    }
}
