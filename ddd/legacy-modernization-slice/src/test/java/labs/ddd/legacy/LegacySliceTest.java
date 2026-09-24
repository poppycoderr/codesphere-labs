package labs.ddd.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import labs.ddd.legacy.old.LegacyRegistrationService;
import labs.ddd.legacy.old.SeamedRegistrationService;
import labs.ddd.legacy.refund.Money;
import labs.ddd.legacy.refund.RefundPolicy;
import labs.ddd.legacy.refund.RefundRouter;
import org.junit.jupiter.api.Test;

/** 退款切片的迁移：字符化测试锁住旧行为 → 切出接缝 → 影子比对 → 分流与回滚。 */
class LegacySliceTest {

    static final Path APPROVED = Path.of("src/test/resources/approved-refunds.tsv");
    static final long START = Instant.parse("2026-10-10T01:00:00Z").toEpochMilli();

    record Case(String id, int feeCents, long minutesBefore) {
        long now() {
            return START - minutesBefore * 60_000;
        }
    }

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        Random random = new Random(2026);
        for (int i = 0; i < 2000; i++) {
            out.add(new Case("R" + i, 100 + random.nextInt(99_900), random.nextInt(200 * 60)));
        }
        long[] edges = {72 * 60, 72 * 60 + 30, 72 * 60 + 59, 73 * 60, 24 * 60, 24 * 60 - 1, 0};
        for (int i = 0; i < edges.length; i++) {
            out.add(new Case("E" + i, 19_950, edges[i]));
        }
        return out;
    }

    static String run(LegacyRegistrationService service) {
        StringBuilder sb = new StringBuilder("id\tfee_cents\tminutes_before\trefund_cents\n");
        for (Case c : cases()) {
            service.register(c.id(), c.feeCents(), START);
            sb.append(c.id()).append('\t').append(c.feeCents()).append('\t').append(c.minutesBefore()).append('\t')
                    .append(service.cancel(c.id(), c.now())).append('\n');
        }
        return sb.toString();
    }

    @Test
    void characterizationLocksTheLegacyBehaviour() throws IOException {
        String actual = run(new LegacyRegistrationService());
        if (Boolean.getBoolean("approve")) {
            Files.writeString(APPROVED, actual, StandardCharsets.UTF_8);
        }
        String approved = Files.readString(APPROVED, StandardCharsets.UTF_8);
        assertEquals(approved, actual);
        String seamed = run(new SeamedRegistrationService(SeamedRegistrationService.ORIGINAL));
        assertEquals(approved, seamed);
        Facts.record("characterize", "旧服务 " + cases().size() + " 个用例与已批准的输出一致；切出接缝后的服务同样一致");
    }

    static int percent(RefundPolicy.HourBoundary boundary, long minutesBefore) {
        RefundPolicy p = new RefundPolicy(Duration.ofHours(72), Duration.ofHours(24), RefundPolicy.Rounding.CENTS, boundary);
        long cents = p.refund(new Money(10_000), Instant.ofEpochMilli(START), Instant.ofEpochMilli(START - minutesBefore * 60_000)).cents();
        return (int) (cents / 100);
    }

    @Test
    void shadowComparisonFindsUndocumentedRules() {
        RefundRouter router = new RefundRouter(SeamedRegistrationService.ORIGINAL, RefundPolicy.natural());
        for (Case c : cases()) {
            router.refund(c.id(), c.feeCents(), START, c.now());
        }
        int tier = 0, rounding = 0;
        List<String> tierExamples = new ArrayList<>();
        for (RefundRouter.Mismatch m : router.mismatches()) {
            Case c = cases().stream().filter(x -> x.id().equals(m.registrationId())).findFirst().orElseThrow();
            if (percent(RefundPolicy.HourBoundary.EXACT, c.minutesBefore()) != percent(RefundPolicy.HourBoundary.TRUNCATE_TO_HOURS, c.minutesBefore())) {
                tier++;
                if (tierExamples.size() < 2) {
                    tierExamples.add("距开场 " + c.minutesBefore() / 60 + " 小时 " + c.minutesBefore() % 60 + " 分：旧 " + m.legacyCents() + " 分，新 " + m.newCents() + " 分");
                }
            } else {
                rounding++;
            }
        }
        assertTrue(tier > 0 && rounding > 0);
        Facts.record("shadow.natural", "按直觉实现的新规则影子比对 " + cases().size() + " 个用例：不一致 " + router.mismatches().size()
                + " 个，其中退款档位不同 " + tier + " 个、只差不足一元的零头 " + rounding + " 个");
        Facts.record("shadow.natural.examples", String.join("；", tierExamples));

        RefundRouter compatible = new RefundRouter(SeamedRegistrationService.ORIGINAL, RefundPolicy.legacyCompatible());
        for (Case c : cases()) {
            compatible.refund(c.id(), c.feeCents(), START, c.now());
        }
        assertEquals(0, compatible.mismatches().size());
        Facts.record("shadow.compatible", "把「截断到整小时」和「抹去零头」写成显式参数后：不一致 " + compatible.mismatches().size() + " 个");
    }

    @Test
    void routingCanBeTurnedUpAndBack() {
        RefundRouter router = new RefundRouter(SeamedRegistrationService.ORIGINAL, RefundPolicy.legacyCompatible());
        List<Case> cases = cases();
        StringBuilder steps = new StringBuilder();
        for (int percent : new int[] {10, 50, 0, 100}) {
            router.route(percent);
            int before = router.routedToNew();
            for (Case c : cases) {
                long got = router.refund(c.id(), c.feeCents(), START, c.now());
                assertEquals(SeamedRegistrationService.ORIGINAL.refundCents(c.feeCents(), START, c.now()), got);
            }
            steps.append(percent).append("% → ").append(router.routedToNew() - before).append(" 个；");
        }
        assertEquals(0, router.mismatches().size());
        Facts.record("routing", "每一档 " + cases.size() + " 次退款，交给新模型的数量：" + steps + "返回值全部与旧算法一致");
    }
}
