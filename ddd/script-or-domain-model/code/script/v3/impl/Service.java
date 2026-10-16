package impl;
import api.Api;
import java.util.*;
public class Service implements Api {
    final Map<String, Integer> capacity = new HashMap<>();
    final Map<String, Integer> reserved = new HashMap<>();
    final Map<String, List<String>> regs = new HashMap<>();
    final Map<String, List<String>> waits = new HashMap<>();
    final Set<String> vips = new HashSet<>();
    public void createSession(String s, int cap) { createSession(s, cap, 0); }
    public void createSession(String s, int cap, int vipSeats) { capacity.put(s, cap); reserved.put(s, vipSeats); regs.put(s, new ArrayList<>()); waits.put(s, new ArrayList<>()); }
    public String register(String s, String p, boolean vip) {
        if (vip) vips.add(p);
        List<String> r = regs.get(s);
        if (r.contains(p) || waits.get(s).contains(p)) return "REJECTED:重复报名";
        long nonVip = r.stream().filter(x -> !vips.contains(x)).count();
        if (r.size() >= capacity.get(s) || (!vip && nonVip >= capacity.get(s) - reserved.get(s))) { waits.get(s).add(p); return "WAITLISTED"; }
        r.add(p);
        return "REGISTERED";
    }
    public void cancel(String s, String p) {
        if (waits.get(s).remove(p)) return;
        if (!regs.get(s).remove(p)) return;
        List<String> r = regs.get(s);
        long nonVip = r.stream().filter(x -> !vips.contains(x)).count();
        for (Iterator<String> it = waits.get(s).iterator(); it.hasNext(); ) {
            String w = it.next();
            if (r.size() < capacity.get(s) && (vips.contains(w) || nonVip < capacity.get(s) - reserved.get(s))) { it.remove(); r.add(w); break; }
        }
    }
    public String transfer(String p, String from, String to) {
        if (!regs.get(from).contains(p)) return "REJECTED:未报名";
        List<String> r = regs.get(to);
        if (r.contains(p) || waits.get(to).contains(p)) return "REJECTED:重复报名";
        long nonVip = r.stream().filter(x -> !vips.contains(x)).count();
        if (r.size() >= capacity.get(to) || (!vips.contains(p) && nonVip >= capacity.get(to) - reserved.get(to))) return "REJECTED:已满";
        r.add(p);
        cancel(from, p);
        return "REGISTERED";
    }
    public List<String> registered(String s) { return List.copyOf(regs.get(s)); }
    public List<String> waitlist(String s) { return List.copyOf(waits.get(s)); }
}
