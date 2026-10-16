package api;
import java.util.List;
/** 两种实现共同遵守的用例接口；验收测试只通过它调用 */
public interface Api {
    void createSession(String session, int capacity);
    default void createSession(String session, int capacity, int reservedForVip) { throw new UnsupportedOperationException(); }   // v3 起
    String register(String session, String person, boolean vip);   // 返回 REGISTERED / WAITLISTED / REJECTED:原因
    void cancel(String session, String person);
    String transfer(String person, String from, String to);       // v2 起
    List<String> registered(String session);
    List<String> waitlist(String session);
}
