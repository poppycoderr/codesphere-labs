/** 打印 JVM 启动后的常驻内存与容器记账，用来对比 -Xms 与 AlwaysPreTouch。 */
public class Rss {
    public static void main(String[] args) throws Exception {
        String rss = "", cur = java.nio.file.Files.readString(java.nio.file.Path.of("/sys/fs/cgroup/memory.current")).trim();
        for (String l : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) if (l.startsWith("VmRSS:")) rss = l.replaceAll("\\D+", "");
        Runtime r = Runtime.getRuntime();
        System.out.println(args[0] + "\tRuntime.totalMemory " + r.totalMemory() / 1048576 + " MB\tVmRSS " + Long.parseLong(rss) / 1024 + " MB\tmemory.current " + Long.parseLong(cur) / 1048576 + " MB");
    }
}
