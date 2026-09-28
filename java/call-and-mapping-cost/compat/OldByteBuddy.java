import net.bytebuddy.ByteBuddy;

/** 在当前 JDK 上用旧版 Byte Buddy 生成一个 Object 的子类。 */
public class OldByteBuddy {
    public static void main(String[] args) throws Exception {
        Class<?> c = new ByteBuddy().subclass(Object.class).make().load(OldByteBuddy.class.getClassLoader()).getLoaded();
        System.out.println("ok " + c.getName());
    }
}
