import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/** 用 ASM 解析参数指定的类文件，报告能否读入。 */
public class AsmParse {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            try {
                new ClassReader(Files.readAllBytes(Path.of(path))).accept(new ClassVisitor(Opcodes.ASM9) { }, 0);
                System.out.println("asm\t" + path + "：解析成功");
            } catch (Exception e) {
                System.out.println("asm\t" + path + "：" + e.getClass().getSimpleName() + "（" + e.getMessage() + "）");
            }
        }
    }
}
