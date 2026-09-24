package labs.ddd.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import labs.ddd.registration.ChargeOnSeatTaken.CreateCharge;
import labs.ddd.registration.Ids.AttendeeId;
import labs.ddd.registration.Ids.SessionId;
import labs.ddd.registration.RegistrationEvent.CandidatePromoted;
import labs.ddd.registration.RegistrationEvent.CandidateWaitlisted;
import labs.ddd.registration.RegistrationEvent.RegistrationCancelled;
import labs.ddd.registration.RegistrationEvent.RegistrationConfirmed;
import labs.ddd.registration.RegistrationEvent.WaitlistLeft;
import labs.ddd.registration.RegistrationRefused.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则表 model/rules.md 的验收测试。每个用例名以规则编号开头，scripts/trace.py 据此检查规则与测试一一对应。
 * 系统属性 model=draft 时检查第一次讨论后的初稿模型。
 */
class RegistrationAcceptanceTest {

    static final SessionId S = new SessionId("S-0921-AM");
    static final Instant STARTS_AT = Instant.parse("2026-10-10T01:00:00Z");
    static final Instant A_WEEK_BEFORE = STARTS_AT.minus(Duration.ofDays(7));
    static final Instant TWO_HOURS_BEFORE = STARTS_AT.minus(Duration.ofHours(2));
    static final AttendeeId ALICE = new AttendeeId("alice");
    static final AttendeeId BOB = new AttendeeId("bob");
    static final AttendeeId CAROL = new AttendeeId("carol");
    static final AttendeeId DAVE = new AttendeeId("dave");

    static RegistrationModel sessionWithCapacity(int capacity) {
        return "draft".equals(System.getProperty("model"))
                ? new DraftSession(S, capacity, STARTS_AT)
                : new Session(S, capacity, STARTS_AT);
    }

    /** Given：用命令把场次推到某个状态，而不是直接写字段。 */
    static RegistrationModel given(int capacity, AttendeeId... registered) {
        RegistrationModel session = sessionWithCapacity(capacity);
        for (AttendeeId a : registered) {
            session.register(a, A_WEEK_BEFORE);
        }
        return session;
    }

    @Test
    @DisplayName("R1 名额未满时报名直接确认")
    void r1() {
        RegistrationModel session = given(2, ALICE);
        List<RegistrationEvent> then = session.register(BOB, A_WEEK_BEFORE);
        assertEquals(List.of(new RegistrationConfirmed(S, BOB)), then);
    }

    @Test
    @DisplayName("R2 名额已满时进入候补并得到顺位")
    void r2() {
        RegistrationModel session = given(1, ALICE);
        assertEquals(List.of(new CandidateWaitlisted(S, BOB, 1)), session.register(BOB, A_WEEK_BEFORE));
        assertEquals(List.of(new CandidateWaitlisted(S, CAROL, 2)), session.register(CAROL, A_WEEK_BEFORE));
    }

    @Test
    @DisplayName("R3 已确认的参会人再次报名被拒绝")
    void r3Confirmed() {
        RegistrationModel session = given(2, ALICE);
        RegistrationRefused refused = assertThrows(RegistrationRefused.class, () -> session.register(ALICE, A_WEEK_BEFORE));
        assertEquals(Reason.DUPLICATE, refused.reason());
    }

    @Test
    @DisplayName("R3 候补中的参会人再次报名被拒绝，不会排两个位置")
    void r3Waitlisted() {
        RegistrationModel session = given(1, ALICE, BOB);
        RegistrationRefused refused = assertThrows(RegistrationRefused.class, () -> session.register(BOB, A_WEEK_BEFORE));
        assertEquals(Reason.DUPLICATE, refused.reason());
    }

    @Test
    @DisplayName("R3 重复点报名不会让一个人占两个名额")
    void r3CancelOnce() {
        RegistrationModel session = given(2, ALICE);
        try {
            session.register(ALICE, A_WEEK_BEFORE);
        } catch (RegistrationRefused expected) {
            // 修订后的模型在这里拒绝；初稿接受了第二次报名
        }
        session.cancel(ALICE, A_WEEK_BEFORE);
        assertEquals(List.of(new RegistrationConfirmed(S, BOB)), session.register(BOB, A_WEEK_BEFORE),
                "Alice 取消后应空出名额");
        assertEquals(List.of(new RegistrationConfirmed(S, CAROL)), session.register(CAROL, A_WEEK_BEFORE),
                "容量为 2，Alice 取消后应有两个空位");
    }

    @Test
    @DisplayName("R4 开场前 24 小时内不能取消")
    void r4() {
        RegistrationModel session = given(1, ALICE);
        RegistrationRefused refused = assertThrows(RegistrationRefused.class, () -> session.cancel(ALICE, TWO_HOURS_BEFORE));
        assertEquals(Reason.TOO_LATE_TO_CANCEL, refused.reason());
    }

    @Test
    @DisplayName("R5 已确认者取消后，候补第一位递补")
    void r5() {
        RegistrationModel session = given(1, ALICE, BOB, CAROL);
        assertEquals(List.of(new RegistrationCancelled(S, ALICE), new CandidatePromoted(S, BOB)), session.cancel(ALICE, A_WEEK_BEFORE));
        assertEquals(List.of(new CandidateWaitlisted(S, DAVE, 2)), session.register(DAVE, A_WEEK_BEFORE), "Carol 仍排第一，Dave 排第二");
    }

    @Test
    @DisplayName("R5 没有候补时取消只释放名额")
    void r5NoWaitlist() {
        RegistrationModel session = given(1, ALICE);
        assertEquals(List.of(new RegistrationCancelled(S, ALICE)), session.cancel(ALICE, A_WEEK_BEFORE));
        assertEquals(List.of(new RegistrationConfirmed(S, BOB)), session.register(BOB, A_WEEK_BEFORE));
    }

    @Test
    @DisplayName("R6 候补者取消只离开队列，不触发递补")
    void r6() {
        RegistrationModel session = given(1, ALICE, BOB, CAROL);
        assertEquals(List.of(new WaitlistLeft(S, BOB)), session.cancel(BOB, TWO_HOURS_BEFORE));
        assertEquals(List.of(new RegistrationCancelled(S, ALICE), new CandidatePromoted(S, CAROL)), session.cancel(ALICE, A_WEEK_BEFORE));
    }

    @Test
    @DisplayName("R7 报名截止后不接受新报名")
    void r7() {
        RegistrationModel session = given(10, ALICE);
        session.closeRegistration();
        RegistrationRefused refused = assertThrows(RegistrationRefused.class, () -> session.register(BOB, A_WEEK_BEFORE));
        assertEquals(Reason.SESSION_CLOSED, refused.reason());
    }

    @Test
    @DisplayName("R8 收费场次有人拿到名额时生成应收，进入候补不生成")
    void r8() {
        RegistrationModel session = given(1);
        ChargeOnSeatTaken policy = new ChargeOnSeatTaken(19_900);
        List<Optional<CreateCharge>> charges = List.of(
                policy.on(session.register(ALICE, A_WEEK_BEFORE).getFirst()),
                policy.on(session.register(BOB, A_WEEK_BEFORE).getFirst()),
                policy.on(session.cancel(ALICE, A_WEEK_BEFORE).getLast()));
        assertEquals(List.of(
                Optional.of(new CreateCharge(S, ALICE, 19_900)),
                Optional.empty(),
                Optional.of(new CreateCharge(S, BOB, 19_900))), charges);
    }

    @Test
    @DisplayName("R8 免费场次不生成应收")
    void r8Free() {
        RegistrationModel session = given(1);
        assertEquals(Optional.empty(), new ChargeOnSeatTaken(0).on(session.register(ALICE, A_WEEK_BEFORE).getFirst()));
    }
}
