package impl;
import api.Api;
import java.util.*;
public class Service implements Api {
    final Map<String, Integer> capacity = new HashMap<>();
    final Map<String, List<String>> regs = new HashMap<>();
    final Map<String, List<String>> waits = new HashMap<>();
    public void createSession(String s, int cap) { capacity.put(s, cap); regs.put(s, new ArrayList<>()); waits.put(s, new ArrayList<>()); }
    public String register(String s, String p, boolean vip) {
        List<String> r = regs.get(s);
        if (r.contains(p) || waits.get(s).contains(p)) return "REJECTED:重复报名";
        if (r.size() >= capacity.get(s)) { waits.get(s).add(p); return "WAITLISTED"; }
        r.add(p);
        return "REGISTERED";
    }
    public void cancel(String s, String p) {
        if (waits.get(s).remove(p)) return;
        if (regs.get(s).remove(p) && !waits.get(s).isEmpty()) regs.get(s).add(waits.get(s).remove(0));
    }
    public String transfer(String p, String from, String to) {
        if (!regs.get(from).contains(p)) return "REJECTED:未报名";
        List<String> r = regs.get(to);
        if (r.contains(p) || waits.get(to).contains(p)) return "REJECTED:重复报名";
        if (r.size() >= capacity.get(to)) return "REJECTED:已满";
        r.add(p);
        cancel(from, p);
        return "REGISTERED";
    }
    public List<String> registered(String s) { return List.copyOf(regs.get(s)); }
    public List<String> waitlist(String s) { return List.copyOf(waits.get(s)); }
}
