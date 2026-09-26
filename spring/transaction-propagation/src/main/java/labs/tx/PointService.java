package labs.tx;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 加积分：一个默认回滚规则的版本，一个声明 noRollbackFor 的版本，二者都在写入前失败。 */
@Service
public class PointService {

    /** 积分服务暂不可用，表示「没有执行」，没有写入半截数据。 */
    public static class PointsUnavailable extends RuntimeException {
        public PointsUnavailable() {
            super("points unavailable");
        }
    }

    @Transactional
    public void add(long userId, int amount) {
        throw new PointsUnavailable();
    }

    @Transactional(noRollbackFor = PointsUnavailable.class)
    public void addLenient(long userId, int amount) {
        throw new PointsUnavailable();
    }
}
