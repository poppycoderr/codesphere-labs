package labs.ddd.layered.application;

import java.util.function.Supplier;

/** 应用层声明的事务端口；提交成功后才执行 afterCommit 回调。 */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);

    void afterCommit(Runnable action);
}
