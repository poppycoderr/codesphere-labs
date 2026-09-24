package labs.creation;

import java.util.ArrayList;
import java.util.List;

/** 活动报名（聚合）：id 与 version 决定身份与并发控制，pendingEvents 是尚未发布的领域事件。 */
public final class Registration {
    public Long id;
    public long version;
    public final String activity;
    public final List<String> attendees;
    public final List<String> pendingEvents;

    public Registration(Long id, long version, String activity, List<String> attendees, List<String> pendingEvents) {
        this.id = id;
        this.version = version;
        this.activity = activity;
        this.attendees = attendees;
        this.pendingEvents = pendingEvents;
    }

    /** 逐字段复制，集合共享引用。 */
    public Registration shallowCopy() {
        return new Registration(id, version, activity, attendees, pendingEvents);
    }

    /** 集合也复制，但身份、版本和未发布的事件原样保留。 */
    public Registration deepCopy() {
        return new Registration(id, version, activity, new ArrayList<>(attendees), new ArrayList<>(pendingEvents));
    }

    /** 以当前报名为模板创建一个新的报名：新身份、新版本，不继承未发布的事件。 */
    public Registration copyAsNew(String newActivity) {
        return new Registration(null, 0, newActivity, new ArrayList<>(attendees), new ArrayList<>());
    }
}
