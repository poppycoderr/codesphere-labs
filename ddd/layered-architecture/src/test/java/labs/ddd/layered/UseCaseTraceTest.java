package labs.ddd.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import labs.ddd.layered.adapter.HttpResponse;
import labs.ddd.layered.adapter.RegisterRequest;
import labs.ddd.layered.infrastructure.SessionRecord;
import labs.ddd.layered.observability.Trace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 一条报名用例穿过四层的顺序，以及三种失败分别停在哪一层。 */
class UseCaseTraceTest {

    Bootstrap app;

    @BeforeEach
    void setUp() {
        app = Bootstrap.wire();
        app.db().seed(new SessionRecord("S-1", 1, 3, List.of(), List.of()));
        Trace.drain();
    }

    @Test
    void happyPath() {
        HttpResponse first = app.controller().post(new RegisterRequest("S-1", "alice", "13800138000"));
        List<String> trace = Trace.drain();
        HttpResponse second = app.controller().post(new RegisterRequest("S-1", "bob", "13900139000"));
        Trace.drain();
        assertEquals(201, first.status());
        assertEquals("WAITLISTED #1", second.body());
        assertEquals(List.of(
                "adapter\t收到 POST /sessions/S-1/registrations",
                "infrastructure\tBEGIN",
                "application\t开始用例 RegisterForSession",
                "infrastructure\tSELECT session S-1",
                "domain\tSession.register 通过容量与重复检查",
                "infrastructure\tUPDATE session ... WHERE version=3：1 行",
                "infrastructure\tCOMMIT",
                "infrastructure\t发布 RegistrationConfirmed",
                "adapter\t返回 201 CONFIRMED"), trace);
        Facts.record("trace.ok", String.join(" → ", trace.stream().map(s -> s.replace('\t', ':')).toList()));
        Facts.record("trace.ok.version", "两次报名后版本 " + app.db().committed("S-1").version() + "，已发布事件 " + app.publisher().published().size() + " 个");
    }

    @Test
    void failures() {
        HttpResponse badPhone = app.controller().post(new RegisterRequest("S-1", "alice", "12345"));
        List<String> t1 = Trace.drain();
        app.controller().post(new RegisterRequest("S-1", "alice", "13800138000"));
        Trace.drain();
        HttpResponse duplicate = app.controller().post(new RegisterRequest("S-1", "alice", "13800138000"));
        List<String> t2 = Trace.drain();
        int publishedBefore = app.publisher().published().size();
        app.db().interleaveCommitBeforeNextSave("S-1");
        HttpResponse conflict = app.controller().post(new RegisterRequest("S-1", "carol", "13700137000"));
        List<String> t3 = Trace.drain();
        assertEquals(400, badPhone.status());
        assertEquals(422, duplicate.status());
        assertEquals(409, conflict.status());
        assertEquals(publishedBefore, app.publisher().published().size());
        assertTrue(t2.contains("infrastructure\tROLLBACK（RegistrationRefused）"));
        Facts.record("trace.bad_phone", badPhone.status() + " " + badPhone.body() + "；经过的层：" + layers(t1));
        Facts.record("trace.duplicate", duplicate.status() + " " + duplicate.body() + "；经过的层：" + layers(t2) + "；" + last2(t2));
        Facts.record("trace.conflict", conflict.status() + " " + conflict.body() + "；" + last2(t3) + "；新发布事件 " + (app.publisher().published().size() - publishedBefore) + " 个");
    }

    private static String layers(List<String> trace) {
        return String.join(",", trace.stream().map(s -> s.substring(0, s.indexOf('\t'))).distinct().toList());
    }

    private static String last2(List<String> trace) {
        return String.join(" → ", trace.subList(trace.size() - 2, trace.size()).stream().map(s -> s.substring(s.indexOf('\t') + 1)).toList());
    }
}
