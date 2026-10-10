import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 发送方和接收方不是同一个版本时，一条消息里多一个字段、少一个字段、多一个枚举值会怎样。
 * JSON 一侧同时用 Jackson 2 与 Jackson 3 的默认配置（两者的包名不同，可以放在同一个类路径上）；
 * Protobuf 一侧用程序构造的描述符和 DynamicMessage，不需要 protoc。
 */
public class SerdeLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    interface Call { Object run() throws Exception; }
    static String attempt(Call c) { try { return String.valueOf(c.run()); } catch (Exception e) { return "抛出 " + e.getClass().getSimpleName(); } }

    public enum Status { CREATED, PAID }
    public record OrderV1(String id, int quantity) { }
    public record OrderBoxed(String id, Integer quantity, String nickname) { }
    public record WithStatus(String id, Status status) { }
    public record Event(String id, Instant at) { }
    public static class OrderOpen {
        public String id; public int quantity;
        private final Map<String, Object> other = new LinkedHashMap<>();
        @JsonAnySetter public void put(String k, Object v) { other.put(k, v); }
        @JsonAnyGetter public Map<String, Object> other() { return other; }
    }

    public static void main(String[] args) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper j2 = new com.fasterxml.jackson.databind.ObjectMapper();
        tools.jackson.databind.ObjectMapper j3 = new tools.jackson.databind.ObjectMapper();
        out("env", "java.version=" + System.getProperty("java.version") + " jackson2=" + com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION
                + " jackson3=" + tools.jackson.databind.cfg.PackageVersion.VERSION + " protobuf=" + com.google.protobuf.RuntimeVersion.OSS_MAJOR + "." + com.google.protobuf.RuntimeVersion.OSS_MINOR + "." + com.google.protobuf.RuntimeVersion.OSS_PATCH);

        // 一、JSON：接收方没见过的字段
        String v2Json = "{\"id\":\"A1\",\"quantity\":2,\"giftWrap\":true}";
        out("json.unknown_field.jackson2", "发送方多了一个 giftWrap 字段，Jackson 2 默认：" + attempt(() -> j2.readValue(v2Json, OrderV1.class)));
        out("json.unknown_field.jackson3", "同一条消息，Jackson 3 默认：" + attempt(() -> j3.readValue(v2Json, OrderV1.class)));

        // 二、JSON：少了字段、字段是 null
        out("json.missing_primitive", "消息里没有 quantity（int）：Jackson 2 " + attempt(() -> j2.readValue("{\"id\":\"A1\"}", OrderV1.class))
                + "；Jackson 3 " + attempt(() -> j3.readValue("{\"id\":\"A1\"}", OrderV1.class)));
        String nullJson = "{\"id\":\"A1\",\"quantity\":null}";
        out("json.null_primitive", "quantity 是 null：Jackson 2 " + attempt(() -> j2.readValue(nullJson, OrderV1.class)) + "；Jackson 3 " + attempt(() -> j3.readValue(nullJson, OrderV1.class)));
        OrderBoxed absent = j3.readValue("{\"id\":\"A1\"}", OrderBoxed.class), explicitNull = j3.readValue("{\"id\":\"A1\",\"nickname\":null}", OrderBoxed.class);
        out("json.absent_vs_null.pojo", "「没传 nickname」与「nickname 传了 null」读进对象之后：" + absent.nickname() + " 与 " + explicitNull.nickname() + "，分不出来");
        tools.jackson.databind.JsonNode n1 = j3.readTree("{\"id\":\"A1\"}"), n2 = j3.readTree("{\"id\":\"A1\",\"nickname\":null}");
        out("json.absent_vs_null.tree", "读成树：没传时 has(\"nickname\") = " + n1.has("nickname") + "；传了 null 时 has = " + n2.has("nickname") + "，isNull = " + n2.get("nickname").isNull());

        // 三、JSON：接收方没见过的枚举值
        String enumJson = "{\"id\":\"A1\",\"status\":\"REFUNDING\"}";
        out("json.unknown_enum", "发送方新增了枚举值 REFUNDING：Jackson 2 " + attempt(() -> j2.readValue(enumJson, WithStatus.class)) + "；Jackson 3 " + attempt(() -> j3.readValue(enumJson, WithStatus.class)));

        // 四、JSON：读出来、改一个字段、写回去
        OrderV1 read = j3.readValue(v2Json, OrderV1.class);
        out("json.round_trip.pojo", "按旧版本的类读入、数量改成 3、再写出：" + j3.writeValueAsString(new OrderV1(read.id(), 3)) + "（giftWrap 没有了）");
        OrderOpen open = j3.readValue(v2Json, OrderOpen.class); open.quantity = 3;
        out("json.round_trip.any", "类上用 @JsonAnySetter / @JsonAnyGetter 收下不认识的字段：" + j3.writeValueAsString(open));

        // 五、JSON：没有类型信息时数字变成什么
        Map<?, ?> map = j3.readValue("{\"small\":42,\"big\":3000000000,\"id\":72477573120020481,\"price\":19.99}", Map.class);
        StringBuilder types = new StringBuilder();
        for (Map.Entry<?, ?> e : map.entrySet()) types.append(e.getKey()).append(" → ").append(e.getValue().getClass().getSimpleName()).append("  ");
        out("json.untyped_numbers", "读成 Map 之后各个值的类型：" + types.toString().trim());
        out("json.untyped_cast", "(Long) map.get(\"small\")：" + attempt(() -> (Long) map.get("small")));

        // 六、JSON：两个大版本的默认输出不同
        Event event = new Event("e1", Instant.parse("2026-10-10T08:00:00Z"));
        out("json.instant.jackson2", "Jackson 2 默认写 Instant：" + attempt(() -> j2.writeValueAsString(event)));
        com.fasterxml.jackson.databind.ObjectMapper j2time = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new JavaTimeModule());
        String t2 = j2time.writeValueAsString(event), t3 = j3.writeValueAsString(event);
        out("json.instant.jackson2_module", "Jackson 2 注册 JavaTimeModule 后：" + t2);
        out("json.instant.jackson3", "Jackson 3 默认：" + t3);
        out("json.instant.cross", "Jackson 3 读 Jackson 2 写的：" + attempt(() -> j3.readValue(t2, Event.class).at()) + "；Jackson 2 读 Jackson 3 写的：" + attempt(() -> j2time.readValue(t3, Event.class).at()));
        String trailing = "{\"id\":\"A1\",\"quantity\":1} trailing";
        out("json.trailing", "合法 JSON 后面跟着多余内容：Jackson 2 " + attempt(() -> j2.readValue(trailing, OrderV1.class)) + "；Jackson 3 " + attempt(() -> j3.readValue(trailing, OrderV1.class)));

        // 七、Protobuf：接收方没见过的字段
        Descriptor orderV1 = message("proto3", "Order", field("id", 1, Type.TYPE_STRING), field("quantity", 2, Type.TYPE_INT32));
        Descriptor orderV2 = message("proto3", "Order", field("id", 1, Type.TYPE_STRING), field("quantity", 2, Type.TYPE_INT32), field("gift_wrap", 3, Type.TYPE_BOOL));
        byte[] fromV2 = DynamicMessage.newBuilder(orderV2).setField(orderV2.findFieldByName("id"), "A1").setField(orderV2.findFieldByName("quantity"), 2)
                .setField(orderV2.findFieldByName("gift_wrap"), true).build().toByteArray();
        DynamicMessage asV1 = DynamicMessage.parseFrom(orderV1, fromV2);
        out("proto.unknown_field", "旧版本解析新版本的消息：quantity = " + asV1.getField(orderV1.findFieldByName("quantity")) + "，不认识的字段编号 " + new TreeSet<>(asV1.getUnknownFields().asMap().keySet()) + " 留在 unknown fields 里");
        byte[] rewritten = asV1.toBuilder().setField(orderV1.findFieldByName("quantity"), 3).build().toByteArray();
        DynamicMessage back = DynamicMessage.parseFrom(orderV2, rewritten);
        out("proto.round_trip", "旧版本把数量改成 3 再发出去，新版本读到：quantity = " + back.getField(orderV2.findFieldByName("quantity")) + "，gift_wrap = " + back.getField(orderV2.findFieldByName("gift_wrap")));

        // 八、Protobuf：字段编号被重新使用
        Descriptor userOld = message("proto3", "User", field("id", 1, Type.TYPE_STRING), field("email", 2, Type.TYPE_STRING), field("user_no", 3, Type.TYPE_INT64));
        byte[] oldUser = DynamicMessage.newBuilder(userOld).setField(userOld.findFieldByName("id"), "u1").setField(userOld.findFieldByName("email"), "a@example.com")
                .setField(userOld.findFieldByName("user_no"), 5_000_000_000L).build().toByteArray();
        Descriptor reuseSameType = message("proto3", "User", field("id", 1, Type.TYPE_STRING), field("nickname", 2, Type.TYPE_STRING), field("user_no", 3, Type.TYPE_INT64));
        out("proto.reuse_same_type", "编号 2 原来是 email（string），删掉后给了 nickname（string）；读旧数据：nickname = " + DynamicMessage.parseFrom(reuseSameType, oldUser).getField(reuseSameType.findFieldByName("nickname")));
        Descriptor reuseOtherType = message("proto3", "User", field("id", 1, Type.TYPE_STRING), field("age", 2, Type.TYPE_INT32), field("user_no", 3, Type.TYPE_INT64));
        DynamicMessage other = DynamicMessage.parseFrom(reuseOtherType, oldUser);
        out("proto.reuse_other_type", "编号 2 给了 age（int32）；读旧数据：age = " + other.getField(reuseOtherType.findFieldByName("age")) + "，编号 " + new TreeSet<>(other.getUnknownFields().asMap().keySet()) + " 进了 unknown fields，不报错");
        Descriptor narrowed = message("proto3", "User", field("id", 1, Type.TYPE_STRING), field("email", 2, Type.TYPE_STRING), field("user_no", 3, Type.TYPE_INT32));
        out("proto.narrowed", "编号 3 从 int64 改成 int32；原值 5000000000 读成 " + DynamicMessage.parseFrom(narrowed, oldUser).getField(narrowed.findFieldByName("user_no")));
        Descriptor renamed = message("proto3", "User", field("id", 1, Type.TYPE_STRING), field("mail_address", 2, Type.TYPE_STRING));
        out("proto.renamed", "编号 2 只改名为 mail_address；读旧数据：" + DynamicMessage.parseFrom(renamed, oldUser).getField(renamed.findFieldByName("mail_address")));

        // 九、Protobuf：0 和没填
        Descriptor implicit = message("proto3", "Stock", field("sku", 1, Type.TYPE_STRING), field("quantity", 2, Type.TYPE_INT32));
        byte[] zero = DynamicMessage.newBuilder(implicit).setField(implicit.findFieldByName("sku"), "S1").setField(implicit.findFieldByName("quantity"), 0).build().toByteArray();
        byte[] unset = DynamicMessage.newBuilder(implicit).setField(implicit.findFieldByName("sku"), "S1").build().toByteArray();
        out("proto.presence.implicit", "proto3 的 int32 quantity = 2：填 0 与不填，序列化结果相同 = " + Arrays.equals(zero, unset) + "（都是 " + zero.length + " 字节），字段能否判断有没有填 = " + implicit.findFieldByName("quantity").hasPresence());
        Descriptor explicit = optionalMessage();
        byte[] zeroOpt = DynamicMessage.newBuilder(explicit).setField(explicit.findFieldByName("sku"), "S1").setField(explicit.findFieldByName("quantity"), 0).build().toByteArray();
        byte[] unsetOpt = DynamicMessage.newBuilder(explicit).setField(explicit.findFieldByName("sku"), "S1").build().toByteArray();
        out("proto.presence.optional", "改成 optional int32 quantity = 2：填 0 是 " + zeroOpt.length + " 字节、不填是 " + unsetOpt.length + " 字节；hasField 分别是 "
                + DynamicMessage.parseFrom(explicit, zeroOpt).hasField(explicit.findFieldByName("quantity")) + " 与 " + DynamicMessage.parseFrom(explicit, unsetOpt).hasField(explicit.findFieldByName("quantity")));

        // 十、Protobuf：接收方没见过的枚举值
        for (String syntax : new String[]{"proto3", "proto2"}) {
            Descriptor oldEnum = enumMessage(syntax, false), newEnum = enumMessage(syntax, true);
            byte[] refunding = DynamicMessage.newBuilder(newEnum).setField(newEnum.findFieldByName("status"), newEnum.getFile().findEnumTypeByName("Status").findValueByName("REFUNDING")).build().toByteArray();
            DynamicMessage seen = DynamicMessage.parseFrom(oldEnum, refunding);
            EnumValueDescriptor value = (EnumValueDescriptor) seen.getField(oldEnum.findFieldByName("status"));
            EnumValueDescriptor relayed = (EnumValueDescriptor) DynamicMessage.parseFrom(newEnum, seen.toByteArray()).getField(newEnum.findFieldByName("status"));
            out("proto.unknown_enum." + syntax, syntax + "：旧版本读到 status = " + value.getName() + "（数值 " + value.getNumber() + "），unknown fields " + new TreeSet<>(seen.getUnknownFields().asMap().keySet())
                    + "；原样转发后新版本读到 " + relayed.getName());
        }
    }

    static FieldDescriptorProto field(String name, int number, Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).build();
    }
    static Descriptor message(String syntax, String name, FieldDescriptorProto... fields) throws Exception {
        DescriptorProto.Builder m = DescriptorProto.newBuilder().setName(name);
        for (FieldDescriptorProto f : fields) m.addField(f);
        return FileDescriptor.buildFrom(FileDescriptorProto.newBuilder().setName(name + ".proto").setSyntax(syntax).addMessageType(m).build(), new FileDescriptor[0]).findMessageTypeByName(name);
    }
    /** proto3 里写 optional：编译器会为它生成一个只含这一个字段的 oneof */
    static Descriptor optionalMessage() throws Exception {
        DescriptorProto.Builder m = DescriptorProto.newBuilder().setName("Stock").addField(field("sku", 1, Type.TYPE_STRING))
                .addField(field("quantity", 2, Type.TYPE_INT32).toBuilder().setProto3Optional(true).setOneofIndex(0))
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_quantity"));
        return FileDescriptor.buildFrom(FileDescriptorProto.newBuilder().setName("StockOptional.proto").setSyntax("proto3").addMessageType(m).build(), new FileDescriptor[0]).findMessageTypeByName("Stock");
    }
    static Descriptor enumMessage(String syntax, boolean withRefunding) throws Exception {
        EnumDescriptorProto.Builder e = EnumDescriptorProto.newBuilder().setName("Status")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("CREATED").setNumber(0)).addValue(EnumValueDescriptorProto.newBuilder().setName("PAID").setNumber(1));
        if (withRefunding) e.addValue(EnumValueDescriptorProto.newBuilder().setName("REFUNDING").setNumber(2));
        DescriptorProto.Builder m = DescriptorProto.newBuilder().setName("Order").addField(field("id", 1, Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder().setName("status").setNumber(2).setType(Type.TYPE_ENUM).setTypeName(".Status").setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
        return FileDescriptor.buildFrom(FileDescriptorProto.newBuilder().setName("OrderEnum.proto").setSyntax(syntax).addEnumType(e).addMessageType(m).build(), new FileDescriptor[0]).findMessageTypeByName("Order");
    }
}
