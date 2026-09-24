package labs.creation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 通知客户端的配置：不变量是「连接超时不超过读超时」「至少一个渠道」。紧凑构造器负责校验并复制渠道列表。 */
public record ClientConfig(
        String endpoint,
        Duration connectTimeout,
        Duration readTimeout,
        List<String> channels
) {
    public ClientConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        if (connectTimeout.compareTo(readTimeout) > 0) {
            throw new IllegalArgumentException("connectTimeout " + connectTimeout + " > readTimeout " + readTimeout);
        }
        if (channels.isEmpty()) throw new IllegalArgumentException("至少一个渠道");
        channels = List.copyOf(channels);
    }
}
