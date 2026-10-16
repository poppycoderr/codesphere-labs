package impl;
import java.util.*;
/** 场次聚合：名额与报名名单的规则都在这里 */
class Session {
    private final int capacity;
    private final List<String> registered = new ArrayList<>();
    Session(int capacity) { this.capacity = capacity; }
    String register(String person, boolean vip) {
        if (registered.contains(person)) return "REJECTED:重复报名";
        if (registered.size() >= capacity) return "REJECTED:已满";
        registered.add(person);
        return "REGISTERED";
    }
    void cancel(String person) { registered.remove(person); }
    List<String> registered() { return List.copyOf(registered); }
    List<String> waitlist() { return List.of(); }
}
