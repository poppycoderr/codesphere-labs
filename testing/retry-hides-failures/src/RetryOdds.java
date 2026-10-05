import java.util.Random;

/** 一个缺陷每次运行有 p 的概率让测试失败：模拟 10 万次构建，统计不同重跑次数下构建失败（缺陷被拦住）的比例。固定种子。 */
public class RetryOdds {
    public static void main(String[] args) {
        int builds = 100_000;
        System.out.println("fail_probability\treruns\tbuilds_failed\tformula");
        for (double p : new double[]{0.5, 0.3, 0.1, 0.02}) {
            for (int reruns : new int[]{0, 1, 2, 3}) {
                Random r = new Random(20261005L);
                int failed = 0;
                for (int b = 0; b < builds; b++) {
                    boolean allFail = true;
                    for (int attempt = 0; attempt <= reruns; attempt++) if (r.nextDouble() >= p) { allFail = false; break; }
                    if (allFail) failed++;
                }
                System.out.printf("%.2f\t%d\t%.3f%%\t%.3f%%%n", p, reruns, 100.0 * failed / builds, 100 * Math.pow(p, reruns + 1));
            }
        }
    }
}
