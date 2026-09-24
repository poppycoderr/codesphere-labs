package labs.ddd.registration;

import java.util.Objects;

/** 报名上下文用到的类型化标识。 */
public final class Ids {
    private Ids() {
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
