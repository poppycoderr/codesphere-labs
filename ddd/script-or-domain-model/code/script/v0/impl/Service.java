package impl;
import api.Api;
import java.util.*;
public class Service implements Api {
    final Map<String, Integer> capacity = new HashMap<>();
    final Map<String, List<String>> regs = new HashMap<>();
    public void createSession(String s, int cap) { capacity.put(s, cap); regs.put(s, new ArrayList<>()); }
    public String register(String s, String p, boolean vip) {
        List<String> r = regs.get(s);
        if (r.contains(p)) return "REJECTED:重复报名";
        if (r.size() >= capacity.get(s)) return "REJECTED:已满";
        r.add(p);
        return "REGISTERED";
    }
    public void cancel(String s, String p) { regs.get(s).remove(p); }
    public String transfer(String p, String from, String to) { throw new UnsupportedOperationException(); }
    public List<String> registered(String s) { return List.copyOf(regs.get(s)); }
    public List<String> waitlist(String s) { return List.of(); }
}
