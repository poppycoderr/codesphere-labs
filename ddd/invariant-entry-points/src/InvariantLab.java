import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 构造器里写了校验，对象就一定合法吗：把同一条不变量放到构造、JSON 反序列化、Java 序列化、反射写字段、
 * 暴露内部集合、可变元素、父类的公共 setter 这几个入口前，看哪些入口绕过了它。
 */
public class InvariantLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "被拒绝（" + e.getClass().getSimpleName() + "：" + rootMessage(e) + "）"; } }
    static String rootMessage(Throwable e) { while (e.getCause() != null) e = e.getCause(); return String.valueOf(e.getMessage()).split("\n")[0]; }

    // 不变量：数量必须在 1 到 99 之间
    public static class Quantity implements Serializable {
        private int value;
        public Quantity() { }                                                   // 框架常要求的无参构造器
        public Quantity(int value) { if (value < 1 || value > 99) throw new IllegalArgumentException("数量必须在 1 到 99 之间：" + value); this.value = value; }
        public int getValue() { return value; }
        @Override public String toString() { return "Quantity(" + value + ")"; }
    }
    /** 修正版：JSON 只能经过构造器创建；Java 反序列化后再校验一次 */
    public static class QuantityChecked implements Serializable {
        private final int value;
        @com.fasterxml.jackson.annotation.JsonCreator
        public QuantityChecked(@com.fasterxml.jackson.annotation.JsonProperty("value") int value) { check(value); this.value = value; }
        static void check(int v) { if (v < 1 || v > 99) throw new IllegalArgumentException("数量必须在 1 到 99 之间：" + v); }
        public int getValue() { return value; }
        private void readObject(ObjectInputStream in) throws java.io.IOException, ClassNotFoundException { in.defaultReadObject(); check(value); }
        @Override public String toString() { return "QuantityChecked(" + value + ")"; }
    }
    public record QuantityRecord(int value) {
        public QuantityRecord { if (value < 1 || value > 99) throw new IllegalArgumentException("数量必须在 1 到 99 之间：" + value); }
    }

    // 聚合：一张购物车最多 3 行
    public static class LineItem { private int qty; LineItem(int q) { qty = q; } public void setQty(int q) { qty = q; } public int qty() { return qty; } @Override public String toString() { return "x" + qty; } }
    public static class Cart {
        private final List<LineItem> lines = new ArrayList<>();
        public void add(int qty) { if (lines.size() >= 3) throw new IllegalStateException("最多 3 行"); if (qty < 1) throw new IllegalArgumentException("数量至少为 1"); lines.add(new LineItem(qty)); }
        public List<LineItem> lines() { return lines; }                          // 直接返回内部列表
        public List<LineItem> linesView() { return Collections.unmodifiableList(lines); }
    }

    // 父类的公共 setter
    public static class BaseEntity { protected String status = "NEW"; public void setStatus(String s) { status = s; } public String status() { return status; } }
    public static class Order extends BaseEntity {
        public void pay() { if (!status.equals("NEW")) throw new IllegalStateException("只有新订单可以支付"); status = "PAID"; }
        public void ship() { if (!status.equals("PAID")) throw new IllegalStateException("未支付不能发货"); status = "SHIPPED"; }
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = JsonMapper.builder().build();
        out("env", "java.version=" + System.getProperty("java.version") + " jackson=" + tools.jackson.databind.cfg.PackageVersion.VERSION);

        out("entry.constructor", "new Quantity(0)：" + attempt(() -> new Quantity(0)));
        out("entry.json_class", "JSON {\"value\":0} 反序列化为带无参构造器的类：" + attempt(() -> json.readValue("{\"value\":0}", Quantity.class)));
        out("entry.json_record", "同样的 JSON 反序列化为 record（经过紧凑构造器）：" + attempt(() -> json.readValue("{\"value\":0}", QuantityRecord.class)));
        Quantity good = new Quantity(5);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(bytes)) { o.writeObject(good); }
        byte[] tampered = bytes.toByteArray();
        for (int i = tampered.length - 1; i >= 0; i--) if (tampered[i] == 5) { tampered[i] = 0; break; }     // 把序列化字节里的 5 改成 0
        out("entry.java_serialization", "Java 序列化的字节被改成 0 之后反序列化：" + attempt(() -> new ObjectInputStream(new ByteArrayInputStream(tampered)).readObject()));
        out("entry.json_creator", "改成只有一个 @JsonCreator 构造器的类，同样的 JSON：" + attempt(() -> json.readValue("{\"value\":0}", QuantityChecked.class)));
        ByteArrayOutputStream bytes2 = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(bytes2)) { o.writeObject(new QuantityChecked(5)); }
        byte[] tampered2 = bytes2.toByteArray();
        for (int i = tampered2.length - 1; i >= 0; i--) if (tampered2[i] == 5) { tampered2[i] = 0; break; }
        out("entry.read_object", "加了 readObject 校验的类，被改过的序列化字节：" + attempt(() -> new ObjectInputStream(new ByteArrayInputStream(tampered2)).readObject()));
        Field f = Quantity.class.getDeclaredField("value"); f.setAccessible(true);
        Quantity viaReflection = new Quantity(5); f.setInt(viaReflection, 0);
        out("entry.reflection", "反射直接写私有字段（ORM 与映射工具常用的方式）：" + viaReflection);

        Cart cart = new Cart(); cart.add(1); cart.add(1); cart.add(1);
        out("aggregate.method", "购物车已有 3 行，再调用 add：" + attempt(() -> { cart.add(1); return "加上了"; }));
        cart.lines().add(new LineItem(1));
        out("aggregate.exposed_list", "从 lines() 拿到内部列表直接 add：现在有 " + cart.lines().size() + " 行");
        Cart cart2 = new Cart(); cart2.add(2);
        out("aggregate.view", "改成返回 unmodifiableList 之后 add：" + attempt(() -> cart2.linesView().add(new LineItem(1))));
        cart2.linesView().get(0).setQty(0);
        out("aggregate.mutable_element", "但列表里的元素本身可变，setQty(0)：购物车里现在是 " + cart2.linesView());

        Order order = new Order();
        out("transition.method", "新订单直接 ship()：" + attempt(() -> { order.ship(); return order.status(); }));
        order.setStatus("SHIPPED");
        out("transition.base_setter", "调用父类的公共 setStatus(\"SHIPPED\")：状态变成 " + order.status() + "，没有经过支付");

        Long a = 127L, b = 127L, c = 128L, d = 128L;
        out("identity.long", "用 == 比较 Long 类型的 id：127 == 127 为 " + (a == b) + "，128 == 128 为 " + (c == d) + "；equals 都为 " + (a.equals(b) && c.equals(d)));
    }
}
