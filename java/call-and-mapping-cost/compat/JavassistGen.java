import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtNewMethod;

/** 在当前 JDK 上用 Javassist 以源码字符串生成一个类并调用它。 */
public class JavassistGen {
    public static void main(String[] args) throws Exception {
        ClassPool pool = ClassPool.getDefault();
        CtClass cc = pool.makeClass("Generated");
        cc.addMethod(CtNewMethod.make("public String hello() { return \"hello\"; }", cc));
        Class<?> c = cc.toClass(JavassistGen.class);
        Object o = c.getDeclaredConstructor().newInstance();
        System.out.println("ok " + c.getMethod("hello").invoke(o));
    }
}
