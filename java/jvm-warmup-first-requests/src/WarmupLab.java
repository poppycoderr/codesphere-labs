import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 进程刚启动时的前几个请求为什么慢：同一个处理函数在一个新的 JVM 里连续调用一万次，记录第 1 次、第 2—10 次、第 91—100 次、
 * 第 901—1000 次、第 9901—10000 次各自的耗时。父进程用不同的 JVM 参数各启动 5 个子进程，取中位数。
 * 子进程模式：child（直接测）、prewarm（先调用 3000 次再测「第一个真实请求」）、train（给 AOT 缓存做训练运行）。
 */
public class WarmupLab {
    static final int CALLS = 10_000, RUNS = 5;

    /** 处理函数用到的类和静态字段都放在这里，第一次调用时才初始化 */
    static final class Handler {
        static final Pattern ITEM = Pattern.compile("(sku\\d+):(\\d+)");
        static final DateTimeFormatter FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        static String handle(int i) throws Exception {
            String raw = "id=" + i + ";items=" + IntStream.range(0, 20).mapToObj(k -> "sku" + ((i * 31 + k * 17) % 97) + ":" + (k % 5 + 1)).collect(Collectors.joining(","));
            Map<String, Integer> qty = new TreeMap<>();
            Matcher m = ITEM.matcher(raw);
            while (m.find()) qty.merge(m.group(1), Integer.parseInt(m.group(2)), Integer::sum);
            String body = qty.entrySet().stream().map(e -> e.getKey() + "x" + e.getValue()).collect(Collectors.joining("|"));
            String time = FORMAT.format(Instant.ofEpochSecond(1_791_619_200L + i).atOffset(ZoneOffset.UTC));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((body + time).getBytes(StandardCharsets.UTF_8));
            return String.format("%s %s %d", time, HexFormat.of().formatHex(digest, 0, 8), body.length());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { child(args[0]); return; }
        System.out.println("env\tjava.version=" + System.getProperty("java.version") + " cpus=" + Runtime.getRuntime().availableProcessors());
        String java = ProcessHandle.current().info().command().orElse("java"), jar = System.getProperty("java.class.path");
        run(List.of(java, "-XX:AOTCacheOutput=/tmp/app.aot", "-cp", jar, "WarmupLab", "train"));
        Map<String, List<String>> modes = new LinkedHashMap<>();
        modes.put("default", List.of());
        modes.put("xint", List.of("-Xint"));
        modes.put("c1_only", List.of("-XX:TieredStopAtLevel=1"));
        modes.put("aot_cache", List.of("-XX:AOTCache=/tmp/app.aot"));
        modes.put("prewarm", List.of());
        for (Map.Entry<String, List<String>> mode : modes.entrySet()) {
            Map<String, List<Double>> samples = new LinkedHashMap<>();
            for (int r = 0; r < RUNS; r++) {
                List<String> cmd = new ArrayList<>(List.of(java)); cmd.addAll(mode.getValue());
                cmd.addAll(List.of("-cp", jar, "WarmupLab", mode.getKey().equals("prewarm") ? "prewarm" : "child"));
                long launched = System.nanoTime();
                Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        // 子进程处理完第一个请求就输出一行 FIRST：从父进程发起启动到这一刻，就是「启动到第一个响应」
                        if (line.equals("FIRST")) { samples.computeIfAbsent("launch_to_first_response_ms", k -> new ArrayList<>()).add((System.nanoTime() - launched) / 1e6); continue; }
                        String[] kv = line.split("\t");
                        if (kv.length == 2) samples.computeIfAbsent(kv[0], k -> new ArrayList<>()).add(Double.parseDouble(kv[1]));
                    }
                }
                if (p.waitFor() != 0) throw new IllegalStateException("子进程失败：" + cmd);
            }
            for (Map.Entry<String, List<Double>> e : samples.entrySet()) {
                double[] v = e.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();
                System.out.println(mode.getKey() + "." + e.getKey() + "\t" + (v[v.length / 2] >= 100 ? String.format("%.0f", v[v.length / 2]) : String.format("%.1f", v[v.length / 2])));
            }
        }
    }

    static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("子进程失败：" + cmd);
        return s;
    }

    static void child(String mode) throws Exception {
        long checksum = 0;
        if (mode.equals("train")) { for (int i = 0; i < CALLS; i++) checksum += Handler.handle(i).length(); System.out.println(checksum); return; }
        if (mode.equals("prewarm")) for (int i = 0; i < 3000; i++) checksum += Handler.handle(-i).length();
        double[] us = new double[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long t = System.nanoTime();
            checksum += Handler.handle(i).length();
            us[i] = (System.nanoTime() - t) / 1000.0;
            if (i == 0) { System.out.println("FIRST"); System.out.flush(); }
        }
        System.out.println("call_1_us\t" + us[0]);
        System.out.println("calls_2_10_us\t" + median(us, 1, 10));
        System.out.println("calls_91_100_us\t" + median(us, 90, 100));
        System.out.println("calls_901_1000_us\t" + median(us, 900, 1000));
        System.out.println("calls_9901_10000_us\t" + median(us, 9900, 10000));
        System.out.println("first_100_total_ms\t" + Arrays.stream(us, 0, 100).sum() / 1000);
        System.out.println("checksum\t" + checksum);
    }
    static double median(double[] a, int from, int to) { double[] c = Arrays.copyOfRange(a, from, to); Arrays.sort(c); return c[c.length / 2]; }
}
