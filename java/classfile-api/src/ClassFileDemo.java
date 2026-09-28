import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Class-File API（java.lang.classfile，JDK 24 起为正式 API）的三件事，输出为「键<TAB>事实」：
 * 1. 从零生成一个类并调用它；
 * 2. 读入 javac 编译的类，把方法里的字符串常量 "old" 换成 "new"，重新加载后调用；
 * 3. 版本边界：解析参数指定的类文件（可能来自更新的 JDK），报告主版本号或失败原因。
 */
public class ClassFileDemo {
    public static void main(String[] args) throws Exception {
        ClassFile cf = ClassFile.of();

        byte[] generated = cf.build(ClassDesc.of("demo.Generated"), cb -> cb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withMethodBody("greet", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.ldc("hello from generated class").areturn()));
        Class<?> g = new Loader().define("demo.Generated", generated);
        out("generate", "生成的类主版本号 %d，调用 greet() 得到「%s」".formatted(cf.parse(generated).majorVersion(), g.getMethod("greet").invoke(null)));

        byte[] original = Files.readAllBytes(Path.of("build/classes21/Target.class"));
        ClassModel model = cf.parse(original);
        CodeTransform replace = (builder, element) -> {
            if (element instanceof ConstantInstruction ci && "old".equals(ci.constantValue())) {
                builder.ldc("new");
            } else {
                builder.with(element);
            }
        };
        byte[] rewritten = cf.transformClass(model, ClassTransform.transformingMethodBodies(replace));
        Class<?> t = new Loader().define("Target", rewritten);
        out("transform", "读入主版本号 %d 的 Target.class，改写后主版本号 %d，调用 greet() 得到「%s」".formatted(
                model.majorVersion(), cf.parse(rewritten).majorVersion(), t.getMethod("greet").invoke(t.getDeclaredConstructor().newInstance())));

        for (String path : args) {
            try {
                ClassModel m = cf.parse(Files.readAllBytes(Path.of(path)));
                out("parse", "%s：主版本号 %d，解析成功".formatted(path, m.majorVersion()));
            } catch (Exception e) {
                out("parse", "%s：%s（%s）".formatted(path, e.getClass().getSimpleName(), e.getMessage()));
            }
        }
    }

    static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    static void out(String key, String fact) {
        System.out.println(key + "\t" + fact);
    }
}
