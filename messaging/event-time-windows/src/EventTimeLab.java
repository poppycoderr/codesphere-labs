import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * 按一分钟的窗口统计每个窗口里的事件数：事件按发生时间归窗，还是按到达时间归窗；晚到的事件算不算；窗口什么时候输出结果。
 * 用 Kafka Streams 自带的 TopologyTestDriver 驱动，事件的时间戳与到达顺序都由测试给出，输出确定。
 */
public class EventTimeLab {
    static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");
    static void out(String k, String v) { System.out.println(k + "\t" + v); }
    record Event(int eventSecond, int arrivalSecond) { }

    /** 返回每个窗口（用窗口开始的分钟数表示）最后一次输出的计数 */
    static Map<String, Long> run(List<Event> events, Duration grace, boolean suppress, boolean byArrival) {
        StreamsBuilder builder = new StreamsBuilder();
        TimeWindows windows = grace == null ? TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)) : TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), grace);
        long retention = 3_600_000L;
        Materialized<String, Long, WindowStore<Bytes, byte[]>> store = Materialized.<String, Long>as(Stores.inMemoryWindowStore("counts", Duration.ofMillis(retention), Duration.ofMinutes(1), false))
                .withKeySerde(Serdes.String()).withValueSerde(Serdes.Long());
        KTable<Windowed<String>, Long> counts = builder.stream("clicks", Consumed.with(Serdes.String(), Serdes.String()))
                .groupByKey(Grouped.with(Serdes.String(), Serdes.String())).windowedBy(windows).count(store);
        if (suppress) counts = counts.suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));
        counts.toStream().map((w, c) -> KeyValue.pair(w.window().startTime().toString().substring(11, 16), c)).to("out", Produced.with(Serdes.String(), Serdes.Long()));
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "event-time-lab");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        Map<String, Long> last = new TreeMap<>();
        try (TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            TestInputTopic<String, String> in = driver.createInputTopic("clicks", Serdes.String().serializer(), Serdes.String().serializer());
            TestOutputTopic<String, Long> o = driver.createOutputTopic("out", Serdes.String().deserializer(), Serdes.Long().deserializer());
            List<Event> ordered = new ArrayList<>(events);
            ordered.sort((a, b) -> Integer.compare(a.arrivalSecond(), b.arrivalSecond()));          // 按到达顺序送入
            for (Event e : ordered) in.pipeInput("page", "click", T0.plusSeconds(byArrival ? e.arrivalSecond() : e.eventSecond()));
            o.readKeyValuesToList().forEach(kv -> last.put(kv.key, kv.value));
        }
        return last;
    }

    public static void main(String[] args) {
        out("env", "java.version=" + System.getProperty("java.version") + " kafka-streams=" + org.apache.kafka.common.utils.AppInfoParser.getVersion());
        out("setup", "一分钟的滚动窗口，统计每个窗口里的点击数；事件写成（发生时刻 → 到达时刻），单位是 08:00:00 之后的秒数");

        // 一、三个事件发生在 08:00 这一分钟，其中一个因为网络晚到了 50 秒；另有两个事件发生在 08:01
        List<Event> delayed = List.of(new Event(10, 11), new Event(20, 21), new Event(55, 105), new Event(70, 71), new Event(80, 81));
        out("truth", "事件：10→11、20→21、55→105（晚到 50 秒）、70→71、80→81。按发生时间，08:00 这一分钟有 3 个，08:01 有 2 个");
        out("by_arrival", "按到达时间归窗：" + run(delayed, Duration.ofMinutes(5), false, true));
        out("by_event_time.grace_5m", "按发生时间归窗，允许晚到 5 分钟：" + run(delayed, Duration.ofMinutes(5), false, false));
        out("by_event_time.no_grace", "按发生时间归窗，不允许晚到：" + run(delayed, null, false, false));
        out("by_event_time.grace_10s", "按发生时间归窗，允许晚到 10 秒（晚到的那个到达时，流里见过的最大时间戳是第 80 秒）：" + run(delayed, Duration.ofSeconds(10), false, false));
        out("by_event_time.grace_30s", "按发生时间归窗，允许晚到 30 秒：" + run(delayed, Duration.ofSeconds(30), false, false));

        // 二、只在窗口关闭后输出一次
        List<Event> quiet = List.of(new Event(10, 11), new Event(20, 21), new Event(70, 71));
        out("suppress.idle", "只在窗口关闭后输出（suppress），事件 10、20、70，之后再没有新事件：" + run(quiet, Duration.ofSeconds(30), true, false));
        List<Event> more = List.of(new Event(10, 11), new Event(20, 21), new Event(70, 71), new Event(95, 96), new Event(155, 156));
        out("suppress.advanced", "同样的设置，后面又来了 95、155 两个事件：" + run(more, Duration.ofSeconds(30), true, false));

        // 三、一个时间戳错得很远的事件
        List<Event> skew = List.of(new Event(10, 11), new Event(3600, 12), new Event(20, 21), new Event(30, 31), new Event(40, 41));
        out("future_timestamp", "事件 10、20、30、40 中间混进一个时间戳在一小时之后的事件（设备时钟错了），允许晚到 5 分钟：" + run(skew, Duration.ofMinutes(5), false, false));
    }
}
