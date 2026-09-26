import java.util.concurrent.TimeUnit;

/**
 * 主线程一秒后把停止标记设为 true，观察工作线程能否退出。
 * 参数 plain 使用普通字段，volatile 使用 volatile 字段；是否解释执行由调用方传 -Xint 决定。
 */
public class StopFlag {
    static boolean plainStop;
    static volatile boolean volatileStop;

    public static void main(String[] args) throws InterruptedException {
        boolean useVolatile = args.length > 0 && args[0].equals("volatile");
        Thread worker = new Thread(() -> {
            long spins = 0;
            if (useVolatile) {
                while (!volatileStop) {
                    spins++;
                }
            } else {
                while (!plainStop) {
                    spins++;
                }
            }
            System.out.println("worker exits after " + (spins > 0 ? "some" : "0") + " spins");
        });
        worker.start();

        TimeUnit.SECONDS.sleep(1);
        if (useVolatile) {
            volatileStop = true;
        } else {
            plainStop = true;
        }
        worker.join(TimeUnit.SECONDS.toMillis(3));
        System.out.println("worker alive after 3s: " + worker.isAlive());
        System.exit(0);
    }
}
