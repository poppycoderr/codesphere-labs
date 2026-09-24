package labs.creation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 两种 Builder：LooseConfig 由 Builder 直接把字段交给对象，不校验、不复制；StrictBuilder 最终交给 record 的紧凑构造器。 */
public final class Builders {
    private Builders() {
    }

    /** 没有不变量检查、直接持有 Builder 列表引用的「配置」。 */
    public static final class LooseConfig {
        public final Duration connectTimeout;
        public final Duration readTimeout;
        public final List<String> channels;

        LooseConfig(Duration connectTimeout, Duration readTimeout, List<String> channels) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.channels = channels;
        }
    }

    public static final class LooseBuilder {
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(5);
        private final List<String> channels = new ArrayList<>();

        public LooseBuilder connectTimeout(Duration d) {
            connectTimeout = d;
            return this;
        }

        public LooseBuilder readTimeout(Duration d) {
            readTimeout = d;
            return this;
        }

        public LooseBuilder channel(String c) {
            channels.add(c);
            return this;
        }

        public LooseConfig build() {
            return new LooseConfig(connectTimeout, readTimeout, channels);
        }
    }

    public static final class StrictBuilder {
        private final String endpoint;
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(5);
        private final List<String> channels = new ArrayList<>();

        public StrictBuilder(String endpoint) {
            this.endpoint = endpoint;
        }

        public StrictBuilder connectTimeout(Duration d) {
            connectTimeout = d;
            return this;
        }

        public StrictBuilder readTimeout(Duration d) {
            readTimeout = d;
            return this;
        }

        public StrictBuilder channel(String c) {
            channels.add(c);
            return this;
        }

        public ClientConfig build() {
            return new ClientConfig(endpoint, connectTimeout, readTimeout, channels);
        }
    }
}
