-- 锁与事务：同一用户重复下单
CREATE TABLE user_order (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT   NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_user (user_id)
);
-- 同样的表加上业务唯一键，作为数据库兜底
CREATE TABLE user_order_unique (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT   NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_user (user_id)
);
-- 状态流转：支付与超时关闭
CREATE TABLE pay_order (
    id        BIGINT PRIMARY KEY,
    status    VARCHAR(16) NOT NULL,
    paid_at   DATETIME(6) NULL,
    closed_at DATETIME(6) NULL
);
