import java.nio.file.*;
import org.apache.flink.connector.datagen.source.DataGeneratorSource;
import org.apache.flink.api.connector.source.util.ratelimit.RateLimiterStrategy;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/** Exercises keyed-state recovery inside one local Flink execution environment. */
public class Recovery {
    public static class Counter extends KeyedProcessFunction<Integer, Long, Long> {
        private transient ValueState<Long> count;
        private transient boolean first;
        @Override
        public void open(OpenContext context) {
            count = getRuntimeContext().getState(new ValueStateDescriptor<>("count", Long.class));
            first = true;
        }
        @Override
        public void processElement(Long value, Context context, Collector<Long> out) throws Exception {
            Long stored = count.value();
            long previous = stored == null ? 0 : stored;
            int attempt = getRuntimeContext().getTaskInfo().getAttemptNumber();
            if (first) {
                Files.writeString(Path.of("/out/attempts.txt"), "attempt=" + attempt + " first_input=" + value + " restored_count=" + previous + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // 状态与数据源位置来自同一个 checkpoint：恢复后的第一条输入正好接在已计数的记录之后
                if (attempt > 0 && (previous <= 0 || previous != value - 1)) {
                    throw new IllegalStateException("restored state does not match source position: first_input=" + value + " restored_count=" + previous);
                }
                first = false;
            }
            if (attempt == 0 && value == 120) {
                throw new IllegalStateException("controlled operator failure");
            }
            count.update(previous + 1);
            out.collect(previous + 1);
        }
    }
    public static void main(String[] args) throws Exception {
        Configuration config = new Configuration();
        config.setString("restart-strategy.type", "fixed-delay");
        config.setString("restart-strategy.fixed-delay.attempts", "2");
        config.setString("restart-strategy.fixed-delay.delay", "100 ms");
        config.setString("state.checkpoints.dir", "file:///tmp/flink-checkpoints");
        config.setString("rest.bind-address", "127.0.0.1");
        var env = StreamExecutionEnvironment.createLocalEnvironment(1, config);
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.enableCheckpointing(100);
        var counts = env.fromSource(new DataGeneratorSource<Long>(index -> index + 1, 200, RateLimiterStrategy.perSecond(40), Types.LONG), WatermarkStrategy.noWatermarks(), "paced-sequence").keyBy(v -> 0).process(new Counter());
        long last = 0;
        try (var values = counts.executeAndCollect()) {
            while (values.hasNext()) last = values.next();
        }
        String attempts = Files.readString(Path.of("/out/attempts.txt"));
        if (last != 200 || !attempts.contains("attempt=1")) {
            throw new AssertionError("expected restored attempt and final count 200; last=" + last);
        }
        System.out.println("flink=" + EnvironmentInformation.getVersion() + " java=" + Runtime.version() + " final_count=" + last);
        System.out.print(attempts);
    }
}
