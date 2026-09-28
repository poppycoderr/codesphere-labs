package labs.call;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;

/** 同一个 greet(String) 的五种调用：直接、JDK 动态代理、反射、Byte Buddy 子类代理、Spring 重打包的 CGLIB 子类代理。代理都只是透传。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CallBench {

    public interface Greeter {
        String greet(String name);
    }

    public static class RealGreeter implements Greeter {
        @Override
        public String greet(String name) {
            return name;
        }
    }

    public static class Passthrough {
        @RuntimeType
        public static Object intercept(@SuperCall java.util.concurrent.Callable<?> zuper) throws Exception {
            return zuper.call();
        }
    }

    Greeter direct;
    Greeter jdkProxy;
    Method method;
    Greeter byteBuddy;
    Greeter cglib;
    String arg = "alice";

    @Setup
    public void setup() throws Exception {
        direct = new RealGreeter();
        Greeter target = new RealGreeter();
        InvocationHandler h = (p, m, a) -> m.invoke(target, a);
        jdkProxy = (Greeter) Proxy.newProxyInstance(Greeter.class.getClassLoader(), new Class<?>[] {Greeter.class}, h);
        method = RealGreeter.class.getMethod("greet", String.class);
        byteBuddy = new ByteBuddy()
                .subclass(RealGreeter.class)
                .method(ElementMatchers.named("greet"))
                .intercept(MethodDelegation.to(Passthrough.class))
                .make()
                .load(CallBench.class.getClassLoader())
                .getLoaded()
                .getDeclaredConstructor()
                .newInstance();
        Enhancer e = new Enhancer();
        e.setSuperclass(RealGreeter.class);
        e.setCallback((MethodInterceptor) (obj, m, a, proxy) -> proxy.invokeSuper(obj, a));
        cglib = (Greeter) e.create();
    }

    @Benchmark
    public String direct() {
        return direct.greet(arg);
    }

    @Benchmark
    public String jdkProxy() {
        return jdkProxy.greet(arg);
    }

    @Benchmark
    public Object reflection() throws Exception {
        return method.invoke(direct, arg);
    }

    @Benchmark
    public String byteBuddy() {
        return byteBuddy.greet(arg);
    }

    @Benchmark
    public String springCglib() {
        return cglib.greet(arg);
    }
}
