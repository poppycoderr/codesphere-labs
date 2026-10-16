package impl;
import java.util.*;
/** 场次聚合：名额与报名名单的规则都在这里 */
class Session {
    private final int capacity;
    private final int reservedForVip;
    private final Map<String, Boolean> registered = new LinkedHashMap<>();   // 人 → 是否 VIP
    private final Map<String, Boolean> waitlist = new LinkedHashMap<>();
    Session(int capacity, int reservedForVip) { this.capacity = capacity; this.reservedForVip = reservedForVip; }
    /** 名额规则只写在这一处：总数不超过名额，非 VIP 不占用保留给 VIP 的席位 */
    private boolean canTake(boolean vip) {
        long nonVip = registered.values().stream().filter(v -> !v).count();
        return registered.size() < capacity && (vip || nonVip < capacity - reservedForVip);
    }
    String register(String person, boolean vip) {
        if (registered.containsKey(person) || waitlist.containsKey(person)) return "REJECTED:重复报名";
        if (!canTake(vip)) { waitlist.put(person, vip); return "WAITLISTED"; }
        registered.put(person, vip);
        return "REGISTERED";
    }
    boolean holds(String person) { return registered.containsKey(person); }
    boolean isVip(String person) { return registered.getOrDefault(person, false); }
    /** 调入：不进候补，满了就拒绝 */
    String transferIn(String person, boolean vip) {
        if (registered.containsKey(person) || waitlist.containsKey(person)) return "REJECTED:重复报名";
        if (!canTake(vip)) return "REJECTED:已满";
        registered.put(person, vip);
        return "REGISTERED";
    }
    void cancel(String person) {
        if (waitlist.remove(person) != null) return;
        if (registered.remove(person) != null) promote();
    }
    private void promote() {
        for (Iterator<Map.Entry<String, Boolean>> it = waitlist.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Boolean> w = it.next();
            if (canTake(w.getValue())) { it.remove(); registered.put(w.getKey(), w.getValue()); return; }
        }
    }
    List<String> registered() { return List.copyOf(registered.keySet()); }
    List<String> waitlist() { return List.copyOf(waitlist.keySet()); }
}
