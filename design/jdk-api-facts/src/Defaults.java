import java.lang.reflect.*;
import java.sql.*;

public class Defaults {
    public static void main(String[] a) throws Exception {
        // 只实现抽象方法（全部抛异常），default 方法走接口自带实现
        Connection c = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, args) -> m.isDefault() ? InvocationHandler.invokeDefault(p, m, args) : null);
        try { c.beginRequest(); System.out.println("beginRequest()：默认什么也不做"); } catch (Exception e) { System.out.println("beginRequest：" + e); }
        try { c.setShardingKey(null); System.out.println("setShardingKey：通过"); } catch (Exception e) { System.out.println("setShardingKey(null)：" + e.getClass().getSimpleName() + " - " + e.getMessage()); }
        try { System.out.println("setShardingKeyIfValid：" + c.setShardingKeyIfValid(null, 1)); } catch (Exception e) { System.out.println("setShardingKeyIfValid：" + e.getClass().getSimpleName() + " - " + e.getMessage()); }
    }
}
