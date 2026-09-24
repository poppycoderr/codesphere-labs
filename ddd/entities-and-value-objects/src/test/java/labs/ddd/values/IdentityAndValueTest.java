package labs.ddd.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import labs.ddd.values.Counterexamples.CopiedTags;
import labs.ddd.values.Counterexamples.DbAssignedRegistration;
import labs.ddd.values.Counterexamples.RawMoney;
import labs.ddd.values.Counterexamples.SeatNumbers;
import labs.ddd.values.Counterexamples.Tags;
import labs.ddd.values.Ids.AttendeeId;
import labs.ddd.values.Ids.RegistrationId;
import labs.ddd.values.Ids.SessionId;
import org.junit.jupiter.api.Test;

class IdentityAndValueTest {

    static final SessionId S = new SessionId("S-1");

    @Test
    void entityKeepsIdentityWhenAttributesChange() {
        RegistrationId id = RegistrationId.next();
        Registration before = new Registration(id, S, new AttendeeId("alice"), Phone.parse("13800138000"));
        Registration after = new Registration(id, S, new AttendeeId("alice"), Phone.parse("13800138000"));
        after.changeContact(Phone.parse("13900139000"));
        Registration sameData = new Registration(RegistrationId.next(), S, new AttendeeId("alice"), Phone.parse("13800138000"));
        assertEquals(before, after);
        assertNotEquals(before, sameData);
        Facts.record("entity", "同一标识、手机号不同：equals=" + before.equals(after) + "；不同标识、属性完全相同：equals=" + before.equals(sameData));
    }

    @Test
    void valueObjectsAreEqualByValue() {
        Money a = Money.of("100", "CNY");
        Money b = Money.of("100.00", "CNY");
        assertNotSame(a, b);
        assertEquals(a, b);
        Money sum = a.plus(Money.of("0.5", "CNY"));
        assertEquals(Money.of("100.50", "CNY"), sum);
        assertEquals(Money.of("100", "CNY"), a, "plus 返回新值，原值不变");
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class, () -> a.plus(Money.of("1", "USD")));
        IllegalArgumentException tooPrecise = assertThrows(IllegalArgumentException.class, () -> Money.of("100.001", "CNY"));
        Facts.record("money", "Money(100) 与 Money(100.00)：equals=" + a.equals(b) + "；a.plus(0.5) 后 a=" + a.amount() + "，结果=" + sum.amount());
        Facts.record("money.rejected", mixed.getMessage() + "；" + tooPrecise.getMessage());
    }

    @Test
    void rawBigDecimalRecordComparesScale() {
        Currency cny = Currency.getInstance("CNY");
        RawMoney a = new RawMoney(new BigDecimal("100.0"), cny);
        RawMoney b = new RawMoney(new BigDecimal("100.00"), cny);
        Set<RawMoney> raw = new HashSet<>(List.of(a, b));
        Set<Money> normalized = new HashSet<>(List.of(new Money(new BigDecimal("100.0"), cny), new Money(new BigDecimal("100.00"), cny)));
        assertNotEquals(a, b);
        assertEquals(0, a.amount().compareTo(b.amount()));
        Facts.record("money.raw", "record 直接包 BigDecimal：100.0 与 100.00 equals=" + a.equals(b) + "，compareTo=" + a.amount().compareTo(b.amount())
                + "，放进 HashSet 后 " + raw.size() + " 个；Money 规范化后 " + normalized.size() + " 个");
    }

    @Test
    void phoneIsNormalizedAtConstruction() {
        Set<Phone> phones = new HashSet<>(List.of(Phone.parse("+86 138-0013-8000"), Phone.parse("13800138000"), Phone.parse("+8613800138000")));
        assertEquals(1, phones.size());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> Phone.parse("12345"));
        IllegalArgumentException capacity = assertThrows(IllegalArgumentException.class, () -> new Capacity(0));
        IllegalArgumentException slot = assertThrows(IllegalArgumentException.class,
                () -> new TimeSlot(Instant.parse("2026-10-10T03:00:00Z"), Instant.parse("2026-10-10T01:00:00Z")));
        Facts.record("phone", "三种写法解析后 " + phones.size() + " 个值：" + phones.iterator().next().e164());
        Facts.record("rejected", bad.getMessage() + "；" + capacity.getMessage() + "；" + slot.getMessage().substring(0, slot.getMessage().indexOf('：')));
    }

    @Test
    void recordIsOnlyShallowlyImmutable() {
        List<String> source = new ArrayList<>(List.of("VIP"));
        Tags tags = new Tags(source);
        CopiedTags copied = new CopiedTags(source);
        source.add("STAFF");
        tags.values().add("PRESS");
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () -> copied.values().add("PRESS"));
        SeatNumbers x = new SeatNumbers(new int[] {1, 2});
        SeatNumbers y = new SeatNumbers(new int[] {1, 2});
        assertFalse(x.equals(y));
        Facts.record("record.list", "修改传入的列表并通过访问器追加后：Tags=" + tags.values() + "，CopiedTags=" + copied.values() + "，向 CopiedTags 追加抛出 " + ex.getClass().getSimpleName());
        Facts.record("record.array", "两个内容都是 [1, 2] 的数组组件：equals=" + x.equals(y));
    }

    @Test
    void identityAssignedOnSaveBreaksSets() {
        DbAssignedRegistration a = new DbAssignedRegistration("alice");
        DbAssignedRegistration b = new DbAssignedRegistration("bob");
        Set<DbAssignedRegistration> beforeSave = new HashSet<>();
        beforeSave.add(a);
        beforeSave.add(b);
        String kept = beforeSave.stream().map(DbAssignedRegistration::attendee).collect(Collectors.joining(","));

        DbAssignedRegistration c = new DbAssignedRegistration("carol");
        Set<DbAssignedRegistration> tracked = new HashSet<>(Set.of(c));
        c.assignId(42);
        boolean found = tracked.contains(c);
        assertEquals(1, beforeSave.size());
        assertFalse(found);

        Set<Registration> typed = new HashSet<>();
        typed.add(new Registration(RegistrationId.next(), S, new AttendeeId("alice"), Phone.parse("13800138000")));
        typed.add(new Registration(RegistrationId.next(), S, new AttendeeId("bob"), Phone.parse("13900139000")));
        assertEquals(2, typed.size());
        Facts.record("entity.null_id", "保存前 id 都为 null：两个报名放进 HashSet 后剩 " + beforeSave.size() + " 个（" + kept + "）；创建时分配标识：" + typed.size() + " 个");
        Facts.record("entity.hash_changes", "放进 HashSet 后再分配 id=42：contains=" + found);
    }

    @Test
    void typedIdentifiersRejectSwappedArguments() {
        String typed = """
                import labs.ddd.values.Ids.*;
                class Caller {
                    void register(SessionId session, AttendeeId attendee) {}
                    void call(SessionId s, AttendeeId a) { register(a, s); }
                }
                """;
        String raw = """
                class RawCaller {
                    void register(String sessionId, String attendeeId) {}
                    void call(String s, String a) { register(a, s); }
                }
                """;
        List<String> typedErrors = compile("Caller", typed);
        List<String> rawErrors = compile("RawCaller", raw);
        assertFalse(typedErrors.isEmpty());
        assertTrue(rawErrors.isEmpty());
        Facts.record("ids.typed", "参数传反：" + typedErrors.getFirst());
        Facts.record("ids.raw", "String 标识参数传反：编译错误 " + rawErrors.size() + " 个");
    }

    private static List<String> compile(String name, String code) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        List<String> options = List.of("-classpath", System.getProperty("java.class.path"), "-d", "target/snippets");
        new java.io.File("target/snippets").mkdirs();
        compiler.getTask(null, null, diagnostics, options, null, List.of(file)).call();
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(Locale.ENGLISH).lines().findFirst().orElse(""))
                .toList();
    }
}
