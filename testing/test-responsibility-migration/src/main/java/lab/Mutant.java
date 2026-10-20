package lab;

/** 变异开关：用系统属性 lab.mutant 故意引入一个缺陷，看哪些测试能发现它 */
final class Mutant {
    private Mutant() {}
    static boolean is(String name) { return name.equals(System.getProperty("lab.mutant", "")); }
}
