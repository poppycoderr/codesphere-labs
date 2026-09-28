/** 在容器里打印 JVM 看到的 CPU 数与最大堆。 */
public class Resources {
    public static void main(String[] args) {
        System.out.printf("availableProcessors=%d maxHeapMB=%d%n", Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1048576);
    }
}
