/** 作为 PID 1 运行、什么也不做的 Java 程序：JVM 自己会处理 SIGTERM。 */
public class Sleeper {
    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("shutdown hook ran")));
        Thread.sleep(Long.MAX_VALUE);
    }
}
