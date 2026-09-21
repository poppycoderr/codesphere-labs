package labs.aop;

import java.util.concurrent.CompletableFuture;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderService implements OrderApi {

    @Autowired Repo repo;

    @Override
    public void create() {
        Recorder.mark("create");
        this.persist();                                        // 自调用，不经过代理
    }

    @Transactional
    public void persist() { Recorder.mark("persist"); }

    public void createViaProxy() {
        ((OrderService) AopContext.currentProxy()).persist();  // 需要 exposeProxy = true
    }

    @Transactional
    public final void finalMethod() {
        Recorder.mark("finalMethod");
        Recorder.EVENTS.add("repo=" + repo);
        System.out.println("    repo = " + repo);
    }

    @Transactional
    void packagePrivate() { Recorder.mark("packagePrivate"); }

    public void callPrivate() { privateTx(); }

    @Transactional
    private void privateTx() { Recorder.mark("privateTx"); }

    @Transactional
    public void switchThread() throws Exception {
        Recorder.mark("callerThread");
        CompletableFuture.runAsync(() -> Recorder.mark("asyncThread")).get();
    }

    @Transactional
    public void checked() throws Exception { throw new Exception("业务校验失败"); }

    @Transactional
    public void unchecked() { throw new IllegalStateException("库存不足"); }
}
