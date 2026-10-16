package impl;
import java.util.*;
/** 场次聚合：名额与报名名单的规则都在这里 */
class Session {
    private final int capacity;
    private final List<String> registered = new ArrayList<>();
    private final List<String> waitlist = new ArrayList<>();
    Session(int capacity) { this.capacity = capacity; }
    String register(String person, boolean vip) {
        if (registered.contains(person) || waitlist.contains(person)) return "REJECTED:重复报名";
        if (registered.size() >= capacity) { waitlist.add(person); return "WAITLISTED"; }
        registered.add(person);
        return "REGISTERED";
    }
    void cancel(String person) {
        if (waitlist.remove(person)) return;
        if (registered.remove(person)) promote();
    }
    private void promote() {
        if (!waitlist.isEmpty()) registered.add(waitlist.remove(0));
    }
    List<String> registered() { return List.copyOf(registered); }
    List<String> waitlist() { return List.copyOf(waitlist); }
}
