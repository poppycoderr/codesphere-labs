import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** java.time 的几组容易混淆的操作：时间点与本地时间、加一天与加 24 小时、月末运算、格式化字母与解析的宽严。全部用固定的日期与时区，输出确定。 */
public class TimeLab {
    static void out(String k, String v) { System.out.println(k + "\t" + v); }

    static String tryParse(String text, DateTimeFormatter f) {
        try { return LocalDate.parse(text, f).toString(); }
        catch (DateTimeException e) { return "抛出 " + e.getClass().getSimpleName(); }
    }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version") + " tzdata=" + java.time.zone.ZoneRulesProvider.getVersions("UTC").lastKey());
        ZoneId ny = ZoneId.of("America/New_York"), sh = ZoneId.of("Asia/Shanghai"), la = ZoneId.of("America/Los_Angeles");

        // 一、同一个时间点在不同时区是不同的日期
        Instant t = Instant.parse("2026-10-09T02:00:00Z");
        out("instant.zones", "时间点 " + t + "：上海 " + t.atZone(sh).toLocalDateTime() + "，洛杉矶 " + t.atZone(la).toLocalDateTime());
        LocalDateTime wall = LocalDateTime.of(2026, 10, 9, 10, 0);
        out("local.ambiguous", "本地时间 " + wall + " 按上海解释是 " + wall.atZone(sh).toInstant() + "，按纽约解释是 " + wall.atZone(ny).toInstant()
                + "，相差 " + Duration.between(wall.atZone(sh).toInstant(), wall.atZone(ny).toInstant()).toHours() + " 小时");

        // 二、加一天与加 24 小时：纽约 2026-03-08 凌晨进入夏令时
        ZonedDateTime before = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, ny);
        out("dst.plus_days", before + " 加 1 天 = " + before.plusDays(1) + "，实际经过 " + Duration.between(before, before.plusDays(1)).toHours() + " 小时");
        out("dst.plus_hours", before + " 加 24 小时 = " + before.plusHours(24));
        out("dst.gap", "纽约不存在的本地时间 2026-03-08T02:30 被解析为 " + LocalDateTime.of(2026, 3, 8, 2, 30).atZone(ny));
        ZonedDateTime overlap = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(ny);
        out("dst.overlap", "纽约出现两次的本地时间 2026-11-01T01:30 默认取 " + overlap + "，另一个是 " + overlap.withLaterOffsetAtOverlap()
                + "，两者相差 " + Duration.between(overlap, overlap.withLaterOffsetAtOverlap()).toMinutes() + " 分钟");
        LocalDate d = LocalDate.of(2026, 3, 8);
        out("dst.day_length", "纽约 2026-03-08 这一天从 " + d.atStartOfDay(ny).toInstant() + " 到 " + d.plusDays(1).atStartOfDay(ny).toInstant()
                + "，共 " + Duration.between(d.atStartOfDay(ny), d.plusDays(1).atStartOfDay(ny)).toHours() + " 小时");

        // 三、月末与间隔
        LocalDate jan31 = LocalDate.of(2026, 1, 31);
        out("month.end", jan31 + " 加 1 个月 = " + jan31.plusMonths(1) + "；再加 1 个月 = " + jan31.plusMonths(1).plusMonths(1) + "；直接加 2 个月 = " + jan31.plusMonths(2));
        LocalDate mar1 = LocalDate.of(2026, 3, 1);
        Period p = Period.between(jan31, mar1);
        out("period.days", jan31 + " 到 " + mar1 + "：Period 是 " + p + "，getDays() = " + p.getDays() + "；ChronoUnit.DAYS.between = " + ChronoUnit.DAYS.between(jan31, mar1));
        out("duration.int_overflow", "30 天的毫秒数用 int 计算：30 * 24 * 60 * 60 * 1000 = " + (30 * 24 * 60 * 60 * 1000) + "；Duration.ofDays(30).toMillis() = " + Duration.ofDays(30).toMillis());

        // 四、格式化字母
        LocalDate dec27 = LocalDate.of(2026, 12, 27);
        out("format.week_year", dec27 + " 用 YYYY-MM-dd（Locale.US）格式化 = " + DateTimeFormatter.ofPattern("YYYY-MM-dd", Locale.US).format(dec27)
                + "；用 yyyy-MM-dd = " + DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).format(dec27));
        LocalDateTime afternoon = LocalDateTime.of(2026, 10, 9, 15, 5);
        out("format.hour", afternoon + " 用 hh:mm 格式化 = " + DateTimeFormatter.ofPattern("hh:mm").format(afternoon) + "；用 HH:mm = " + DateTimeFormatter.ofPattern("HH:mm").format(afternoon));
        out("format.day_of_year", LocalDate.of(2026, 2, 10) + " 用 yyyy-MM-DD 格式化 = " + DateTimeFormatter.ofPattern("yyyy-MM-DD").format(LocalDate.of(2026, 2, 10)));

        // 五、解析的宽严
        DateTimeFormatter smart = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter strictY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withResolverStyle(ResolverStyle.STRICT);
        DateTimeFormatter strictU = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
        out("parse.smart", "默认（SMART）解析 2026-02-30 = " + tryParse("2026-02-30", smart) + "；解析 2026-02-32 = " + tryParse("2026-02-32", smart));
        out("parse.strict_yyyy", "STRICT 加 yyyy 解析合法日期 2026-02-10 = " + tryParse("2026-02-10", strictY));
        out("parse.strict_uuuu", "STRICT 加 uuuu 解析 2026-02-10 = " + tryParse("2026-02-10", strictU) + "；解析 2026-02-30 = " + tryParse("2026-02-30", strictU));

        // 六、相等
        ZonedDateTime a = ZonedDateTime.of(2026, 10, 9, 10, 0, 0, 0, sh), b = a.withZoneSameInstant(ZoneId.of("UTC"));
        out("equality", a + " 与 " + b + "：equals = " + a.equals(b) + "，isEqual = " + a.isEqual(b) + "，toInstant 相等 = " + a.toInstant().equals(b.toInstant()));

        // 七、旧 API
        @SuppressWarnings("deprecation")
        java.util.Date legacy = new java.util.Date(2026, 10, 9);
        out("legacy.date", "new Date(2026, 10, 9) 表示的日期是 " + legacy.toInstant().atZone(ZoneId.of("UTC")).toLocalDate());
    }
}
