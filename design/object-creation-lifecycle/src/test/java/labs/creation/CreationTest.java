package labs.creation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CreationTest {

    @Test
    void looseBuilderLetsInvalidAndMutableObjectsEscape() {
        Builders.LooseBuilder b = new Builders.LooseBuilder()
                .connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(1)).channel("sms");
        Builders.LooseConfig cfg = b.build();
        assertTrue(cfg.connectTimeout.compareTo(cfg.readTimeout) > 0);
        b.channel("email");
        assertEquals(List.of("sms", "email"), cfg.channels);
        Facts.record("loose.invalid_built", "connect=" + cfg.connectTimeout + " read=" + cfg.readTimeout);
        Facts.record("loose.channels_after_builder_reused", cfg.channels);
    }

    @Test
    void strictBuilderDelegatesInvariantsToTheRecord() {
        Builders.StrictBuilder b = new Builders.StrictBuilder("https://sms.example")
                .connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(1)).channel("sms");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, b::build);
        Facts.record("strict.build_error", e.getMessage());
        Builders.StrictBuilder ok = new Builders.StrictBuilder("https://sms.example").channel("sms");
        ClientConfig cfg = ok.build();
        ok.channel("email");
        assertEquals(List.of("sms"), cfg.channels());
        assertThrows(UnsupportedOperationException.class, () -> cfg.channels().add("x"));
        Facts.record("strict.channels_after_builder_reused", cfg.channels());
        List<String> external = new ArrayList<>(List.of("sms"));
        ClientConfig direct = new ClientConfig("https://sms.example", Duration.ofSeconds(1), Duration.ofSeconds(5), external);
        external.add("email");
        assertEquals(List.of("sms"), direct.channels());
    }

    @Test
    void staticSingletonIsUniquePerClassLoader() throws Exception {
        URL classes = Path.of("target", "classes").toUri().toURL();
        Object a;
        Object b;
        int countA;
        int countB;
        try (URLClassLoader l1 = new URLClassLoader(new URL[] {classes}, null);
             URLClassLoader l2 = new URLClassLoader(new URL[] {classes}, null)) {
            Class<?> c1 = l1.loadClass("labs.creation.Registry");
            Class<?> c2 = l2.loadClass("labs.creation.Registry");
            a = c1.getField("INSTANCE").get(null);
            b = c2.getField("INSTANCE").get(null);
            c1.getMethod("register").invoke(a);
            c1.getMethod("register").invoke(a);
            countA = (int) c1.getMethod("register").invoke(a);
            countB = (int) c2.getMethod("register").invoke(b);
        }
        assertNotSame(a, b);
        assertEquals(3, countA);
        assertEquals(1, countB);
        Facts.record("singleton.classloaders", "2 个类加载器各有一个 INSTANCE，计数分别为 " + countA + " 与 " + countB);
    }

    @Test
    void springSingletonIsUniquePerContextAndPrototypeInjectionIsStale() {
        try (var ctx1 = new AnnotationConfigApplicationContext(ScopedBeans.class);
             var ctx2 = new AnnotationConfigApplicationContext(ScopedBeans.class)) {
            ScopedBeans.Sender s1 = ctx1.getBean(ScopedBeans.Sender.class);
            assertSame(s1, ctx1.getBean(ScopedBeans.Sender.class));
            assertNotSame(s1, ctx2.getBean(ScopedBeans.Sender.class));
            assertNotSame(ctx1.getBean(ScopedBeans.RequestContext.class), ctx1.getBean(ScopedBeans.RequestContext.class));
            assertSame(s1.injectedContext(), s1.injectedContext());
            assertNotSame(s1.freshContext(), s1.freshContext());
            Facts.record("spring.singleton", "同一容器两次 getBean 为同一对象，两个容器各一个");
            Facts.record("spring.prototype_injected_into_singleton", "直接注入：每次都是同一个 prototype 实例；ObjectProvider：每次新实例");
        }
    }

    @Test
    void shallowCopySharesCollections() {
        Registration original = new Registration(1L, 3, "java-meetup", new ArrayList<>(List.of("alice")), new ArrayList<>());
        Registration shallow = original.shallowCopy();
        shallow.attendees.add("mallory");
        assertEquals(List.of("alice", "mallory"), original.attendees);
        Registration deep = original.deepCopy();
        deep.attendees.add("bob");
        assertEquals(List.of("alice", "mallory"), original.attendees);
        Facts.record("copy.shallow_polluted_original", original.attendees);
    }

    @Test
    void copyingAnAggregateMustResetIdentity() {
        Repository repo = new Repository();
        Registration original = repo.save(new Registration(null, 0, "java-meetup", new ArrayList<>(List.of("alice")),
                new ArrayList<>(List.of("RegistrationOpened"))));
        long id = original.id;

        Registration clone = original.deepCopy();
        clone.attendees.clear();
        clone.attendees.add("template-user");
        repo.save(clone);
        assertEquals(1, repo.size());
        assertEquals(List.of("template-user"), repo.find(id).attendees);
        Facts.record("copy.deep_copy_saved_over_original", "仓储中只有 " + repo.size() + " 条，id " + id + " 的名单变成 " + repo.find(id).attendees);
        IllegalStateException conflict = assertThrows(IllegalStateException.class, () -> repo.save(original));
        Facts.record("copy.original_then_conflicts", conflict.getMessage());

        Repository repo2 = new Repository();
        Registration o2 = repo2.save(new Registration(null, 0, "java-meetup", new ArrayList<>(List.of("alice")),
                new ArrayList<>(List.of("RegistrationOpened"))));
        Registration copy = o2.copyAsNew("kotlin-meetup");
        repo2.save(copy);
        assertEquals(2, repo2.size());
        assertTrue(copy.pendingEvents.isEmpty());
        Facts.record("copy.copy_as_new", "新 id " + copy.id + "、version " + copy.version + "、未发布事件 " + copy.pendingEvents.size() + " 个；原报名 id " + o2.id + " 不变");
    }
}
