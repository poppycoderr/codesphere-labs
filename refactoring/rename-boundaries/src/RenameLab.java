import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Method;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在 IDE 里改一个名字，编译器能检查到的调用点都会跟着改；编译器看不到的地方不会。
 * 这里把同一个改名放到四种「按名字找」的地方：JSON 字段、反射、按名字存下来的枚举、按名字查的配置键，看旧数据和旧调用方会怎样。
 */
public class RenameLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    // 改名前后的两个版本：字段 qty 改成更达意的 quantity
    public record OrderLineV1(String sku, int qty) { }
    public record OrderLineV2(String sku, int quantity) { }
    public record OrderLineV2Compat(String sku, @JsonProperty("qty") int quantity) { }

    public enum StatusV1 { WAIT_PAY, PAID }
    public enum StatusV2 { AWAITING_PAYMENT, PAID }
    public enum StatusV3 { CREATED, WAIT_PAY, PAID }

    public static class ServiceV1 { public String calc(int n) { return "fee=" + n * 2; } }
    public static class ServiceV2 { public String calculateFee(int n) { return "fee=" + n * 2; } }

    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName() + "（" + e.getMessage().split("\n")[0] + "）"; } }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = JsonMapper.builder().build();
        out("env", "java.version=" + System.getProperty("java.version") + " jackson=" + tools.jackson.databind.cfg.PackageVersion.VERSION);

        // 一、JSON：字段名就是对外的名字
        String oldMessage = json.writeValueAsString(new OrderLineV1("A1", 3));
        String newMessage = json.writeValueAsString(new OrderLineV2("A1", 3));
        out("json.wire", "改名前写出 " + oldMessage + "；改名后写出 " + newMessage);
        out("json.old_reader", "还没升级的消费方（按 qty 读、忽略不认识的字段）读新消息：" + json.readValue(newMessage, OrderLineV1.class));
        out("json.new_reader", "已升级的一方读队列里积压的旧消息：" + json.readValue(oldMessage, OrderLineV2.class));
        out("json.compat", "改代码里的名字、用 @JsonProperty 保留对外名字：写出 " + json.writeValueAsString(new OrderLineV2Compat("A1", 3))
                + "，读旧消息得到 " + json.readValue(oldMessage, OrderLineV2Compat.class));

        // 二、反射：按字符串找方法
        out("reflection.old", "配置里写着方法名 calc，改名前反射调用：" + attempt(() -> ServiceV1.class.getMethod("calc", int.class).invoke(new ServiceV1(), 5)));
        out("reflection.new", "方法改名为 calculateFee 后，同一份配置：" + attempt(() -> ServiceV2.class.getMethod("calc", int.class).invoke(new ServiceV2(), 5)));

        // 三、按名字存下来的枚举
        String stored = StatusV1.WAIT_PAY.name();                         // 数据库或消息里存的是 name()
        out("enum.stored", "数据库里存的是 " + stored + "；枚举常量改名为 AWAITING_PAYMENT 之后读回：" + attempt(() -> StatusV2.valueOf(stored)));
        int ordinal = StatusV1.WAIT_PAY.ordinal();
        out("enum.ordinal", "如果存的是序号 ordinal()：WAIT_PAY 存为 " + ordinal + "；新版本在最前面插入 CREATED 之后，按序号读回来是 " + StatusV3.values()[ordinal]);

        // 四、按名字查的配置键
        Map<String, String> config = Map.of("order.max-qty", "50");
        out("config", "代码把配置键 order.max-qty 改成 order.max-quantity，部署环境里的配置没改：读到 " + config.get("order.max-quantity") + "，回退到代码里的默认值");

        // 编译器负责的部分
        Method m = ServiceV2.class.getMethod("calculateFee", int.class);
        out("compiler", "直接调用的地方由编译器保证：new ServiceV2().calculateFee(5) = " + m.invoke(new ServiceV2(), 5));
    }
}
