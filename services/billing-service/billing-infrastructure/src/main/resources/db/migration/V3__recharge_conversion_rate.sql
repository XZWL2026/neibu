CREATE TABLE recharge_conversion_rate (
    id TINYINT NOT NULL PRIMARY KEY,
    points_per_yuan4 DECIMAL(38,4) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT chk_recharge_rate_singleton CHECK (id = 1),
    CONSTRAINT chk_recharge_rate_positive CHECK (points_per_yuan4 > 0),
    CONSTRAINT chk_recharge_rate_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO recharge_conversion_rate (id, points_per_yuan4, version) VALUES (1, 10.0000, 1);
