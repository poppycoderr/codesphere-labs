package impl;
import api.Api;
import java.util.*;
/** 应用服务：取出聚合、调用方法；没有业务判断 */
public class Service implements Api {
    final Map<String, Session> sessions = new HashMap<>();
    public void createSession(String s, int cap) { createSession(s, cap, 0); }
    public void createSession(String s, int cap, int vipSeats) { sessions.put(s, new Session(cap, vipSeats)); }
    public String register(String s, String p, boolean vip) { return sessions.get(s).register(p, vip); }
    public void cancel(String s, String p) { sessions.get(s).cancel(p); }
    public String transfer(String p, String from, String to) {
        if (!sessions.get(from).holds(p)) return "REJECTED:未报名";
        String r = sessions.get(to).transferIn(p, sessions.get(from).isVip(p));
        if (r.equals("REGISTERED")) sessions.get(from).cancel(p);
        return r;
    }
    public List<String> registered(String s) { return sessions.get(s).registered(); }
    public List<String> waitlist(String s) { return sessions.get(s).waitlist(); }
}
