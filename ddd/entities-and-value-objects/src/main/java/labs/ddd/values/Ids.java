package labs.ddd.values;

import java.util.Objects;
import java.util.UUID;

/** 类型化标识：创建时就生成，不等数据库分配。 */
public final class Ids {
    private Ids() {
    }

    public record RegistrationId(UUID value) {
        public RegistrationId {
            Objects.requireNonNull(value);
        }

        public static RegistrationId next() {
            return new RegistrationId(UUID.randomUUID());
        }
    }

    public record SessionId(String value) {
        public SessionId {
            Objects.requireNonNull(value);
        }
    }

    public record AttendeeId(String value) {
        public AttendeeId {
            Objects.requireNonNull(value);
        }
    }
}
