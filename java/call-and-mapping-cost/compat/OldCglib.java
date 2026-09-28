import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;

/** 在当前 JDK 上用原版 CGLIB 生成一个子类代理。 */
public class OldCglib {
    public static class Target {
        public String hello() {
            return "hello";
        }
    }

    public static void main(String[] args) {
        Enhancer e = new Enhancer();
        e.setSuperclass(Target.class);
        e.setCallback((MethodInterceptor) (obj, m, a, proxy) -> proxy.invokeSuper(obj, a));
        System.out.println("ok " + ((Target) e.create()).hello());
    }
}
