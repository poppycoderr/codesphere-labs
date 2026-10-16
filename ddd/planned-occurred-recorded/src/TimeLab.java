import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一件业务事实可能有三个时刻：计划在什么时候发生、实际在什么时候发生、系统在什么时候知道。
 * 用 createdAt/updatedAt 代替它们、把计划时刻存成 Instant、按收到的时间统计迟到的事实：各自得到什么结果。全部用固定的时间，输出确定。
 */
public class TimeLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    static Instant at(String s) { return Instant.parse(s); }

    // 一、用 updatedAt 当支付时间
    static final class OrderWithAuditOnly { Instant createdAt, updatedAt; String status = "NEW", address = "A"; }
    static final class OrderWithFacts { Instant createdAt, paidAt; String status = "NEW", address = "A"; }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version"));

        OrderWithAuditOnly o1 = new OrderWithAuditOnly();
        o1.createdAt = at("2026-10-15T01:00:00Z"); o1.status = "PAID"; o1.updatedAt = at("2026-10-15T01:05:00Z");   // 支付
        o1.address = "B"; o1.updatedAt = at("2026-10-16T08:00:00Z");                                               // 第二天改了收货地址
        OrderWithFacts o2 = new OrderWithFacts();
        o2.createdAt = at("2026-10-15T01:00:00Z"); o2.status = "PAID"; o2.paidAt = at("2026-10-15T01:05:00Z");
        o2.address = "B";
        out("audit_as_fact", "支付后第二天改了收货地址：用 updatedAt 当支付时间，报表里这笔订单的支付时间是 " + o1.updatedAt + "；单独记录 paidAt 的是 " + o2.paidAt);

        // 二、计划时刻：存本地时间加时区，还是存 Instant
        ZoneId ny = ZoneId.of("America/New_York");
        LocalTime planned = LocalTime.of(9, 0);
        Instant computedOnce = ZonedDateTime.of(LocalDate.of(2026, 10, 30), planned, ny).toInstant();       // 夏令时期间算出的 Instant
        List<String> fixed = new ArrayList<>(), local = new ArrayList<>();
        for (LocalDate day : List.of(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2))) {
            long days = Duration.between(LocalDate.of(2026, 10, 30).atStartOfDay(), day.atStartOfDay()).toDays();
            fixed.add(day + " " + computedOnce.plus(Duration.ofDays(days)).atZone(ny).toLocalTime());
            local.add(day + " " + ZonedDateTime.of(day, planned, ny).toLocalTime());
        }
        out("planned.instant", "纽约用户约定每天 9:00 提醒：把第一次的 Instant 每天加 24 小时，夏令时结束后在当地 " + fixed);
        out("planned.local", "存「本地时间 09:00 + 时区」、每次按当天计算：" + local);

        // 三、迟到的事实
        record Reading(String device, Instant occurredAt, Instant receivedAt, int value) { }
        List<Reading> readings = List.of(
                new Reading("d1", at("2026-10-15T15:50:00Z"), at("2026-10-15T15:50:02Z"), 10),
                new Reading("d2", at("2026-10-15T15:58:00Z"), at("2026-10-16T00:30:00Z"), 20),       // 设备离线，第二天才补传
                new Reading("d1", at("2026-10-16T01:00:00Z"), at("2026-10-16T01:00:01Z"), 5));
        ZoneId utc = ZoneId.of("UTC");
        Map<LocalDate, Integer> byReceived = new TreeMap<>(), byOccurred = new TreeMap<>();
        for (Reading r : readings) {
            byReceived.merge(r.receivedAt().atZone(utc).toLocalDate(), r.value(), Integer::sum);
            byOccurred.merge(r.occurredAt().atZone(utc).toLocalDate(), r.value(), Integer::sum);
        }
        out("late.by_received", "按系统收到的日期汇总：" + byReceived);
        out("late.by_occurred", "按实际发生的日期汇总：" + byOccurred);
        Instant reportClosed = at("2026-10-16T00:10:00Z");
        out("late.after_close", "10-15 的日报在 " + reportClosed + " 已经发出：d2 的读数 " + (readings.get(1).receivedAt().isAfter(reportClosed) ? "在日报发出之后才到，需要一条更正记录（10-15 由 10 改为 30），而不是悄悄改掉已发出的数字" : "在日报之前到达"));

        // 四、取消与执行的先后
        Instant scheduledRun = at("2026-10-16T02:00:00Z"), actualRun = at("2026-10-16T02:00:03Z");
        Instant cancelRequestedAt = at("2026-10-16T01:59:58Z"), cancelReceivedAt = at("2026-10-16T02:00:05Z");
        out("cancel.by_received", "用户 " + cancelRequestedAt + " 点了取消，请求 " + cancelReceivedAt + " 才到；任务 " + actualRun + " 已执行。按「收到时间」判断：" + (cancelReceivedAt.isAfter(actualRun) ? "取消晚于执行，拒绝取消" : "取消在执行前，取消成功"));
        out("cancel.by_requested", "按「用户提出的时间」判断：取消早于计划时刻 " + scheduledRun + "，" + (cancelRequestedAt.isBefore(scheduledRun) ? "应当视为已取消，需要补偿已执行的动作" : "拒绝") + "；规则选哪一种是业务决定，两个时间都要记下来");

        // 五、计划时刻与实际时刻之差
        record Job(Instant planned, Instant actual) { }
        List<Job> jobs = List.of(new Job(at("2026-10-16T02:00:00Z"), at("2026-10-16T02:00:03Z")), new Job(at("2026-10-16T02:05:00Z"), at("2026-10-16T02:19:00Z")), new Job(at("2026-10-16T02:10:00Z"), at("2026-10-16T02:19:30Z")));
        long late = jobs.stream().filter(j -> Duration.between(j.planned(), j.actual()).toMinutes() >= 5).count();
        out("schedule.delay", "3 个计划任务，按「实际执行时刻 − 计划时刻 ≥ 5 分钟」算迟到：" + late + " 个；只记执行时刻（没有计划时刻）就算不出来");
    }
}
