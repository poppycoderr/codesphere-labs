import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容相同的字符串占多少内存：从外部读入 200 万条记录，每条有一个「城市」字段，取值只有 100 种。
 * 比较四种做法下这 200 万个字段占用的堆：每条各建一个 String、String.intern()、自己的 HashMap 归一、G1 的字符串去重。
 * 每种做法在一个新的子进程里运行，父进程汇总。另外量一下单个字符串的大小与一个非 Latin-1 字符的影响。
 */
public class StringLab {
    static final int RECORDS = 2_000_000, DISTINCT = 100;

    static long used() throws Exception {
        for (int i = 0; i < 4; i++) { System.gc(); Thread.sleep(150); }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /** 模拟从网络或文件里解析出字段：每次都从字节新建一个 String */
    static String parse(int i, String suffix) { return new String(("city-of-somewhere-" + (i % DISTINCT) + suffix).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { child(args[0]); return; }
        System.out.println("env\tjava.version=" + System.getProperty("java.version"));
        System.out.println("setup\t" + RECORDS + " 条记录，每条一个约 20 个字符的城市字段，取值共 " + DISTINCT + " 种");
        String java = ProcessHandle.current().info().command().orElse("java");
        String[][] modes = {
                {"plain", "每条记录各建一个 String"}, {"intern", "解析后调用 intern()"}, {"map", "用自己的 HashMap 归一成同一个对象"},
                {"dedup", "每条各建一个 String，加 -XX:+UseStringDeduplication", "-XX:+UseStringDeduplication"},
                {"plain_cjk", "每条各建一个 String，字段末尾多一个汉字"}};
        for (String[] m : modes) {
            List<String> cmd = new ArrayList<>(List.of(java, "-Xmx1g", "-XX:+UseG1GC"));
            if (m.length > 2) cmd.add(m[2]);
            cmd.addAll(List.of("src/StringLab.java", m[0]));
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            System.out.println(m[0] + ".bytes_per_record\t" + s + "\t" + m[1]);
        }
    }

    static void child(String mode) throws Exception {
        long before = used();
        String[] fields = new String[RECORDS];
        Map<String, String> canon = new HashMap<>();
        String suffix = mode.equals("plain_cjk") ? "市" : "";
        long t = System.nanoTime();
        for (int i = 0; i < RECORDS; i++) {
            String s = parse(i, suffix);
            if (mode.equals("intern")) s = s.intern();
            else if (mode.equals("map")) { String c = canon.putIfAbsent(s, s); if (c != null) s = c; }
            fields[i] = s;
        }
        long ms = (System.nanoTime() - t) / 1_000_000;
        if (mode.equals("dedup")) { for (int i = 0; i < 6; i++) { System.gc(); Thread.sleep(300); } }   // 去重在垃圾回收之后由后台线程完成
        long after = used();
        // 减去引用数组本身（每个引用按压缩指针 4 字节计）
        System.out.printf("%.1f\t%d%n", (after - before - 4.0 * RECORDS) / RECORDS, ms);
        if (fields[RECORDS - 1] == null) throw new IllegalStateException();
    }
}
