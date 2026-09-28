-- 短链：同一个长 URL 是否复用同一个短码
CREATE TABLE short_link_nounique (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    code     VARCHAR(16)  NOT NULL,
    url_hash BINARY(32)   NOT NULL,
    url      VARCHAR(2048) NOT NULL,
    KEY idx_hash (url_hash)
);
CREATE TABLE short_link (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    code     VARCHAR(16)  NOT NULL,
    url_hash BINARY(32)   NOT NULL,
    url      VARCHAR(2048) NOT NULL,
    UNIQUE KEY uk_code (code),
    UNIQUE KEY uk_hash (url_hash)
);
