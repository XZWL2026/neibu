CREATE TABLE iam_admin_lock (
    lock_id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_iam_admin_lock CHECK (lock_id = 1)
) ENGINE=InnoDB;
INSERT INTO iam_admin_lock(lock_id) VALUES(1);

CREATE TABLE iam_admin (
    admin_id VARCHAR(36) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    primary_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_primary THEN 1 ELSE NULL END) STORED,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_iam_admin_username UNIQUE (username),
    CONSTRAINT uk_iam_admin_primary UNIQUE (primary_slot),
    CONSTRAINT ck_iam_admin_role CHECK (role IN ('ADMIN','NORMAL_ADMIN')),
    CONSTRAINT ck_iam_admin_primary_role CHECK (NOT is_primary OR role='ADMIN')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE iam_phone_otp (
    phone VARCHAR(16) NOT NULL PRIMARY KEY,
    code_hash CHAR(64),
    expires_at BIGINT NOT NULL DEFAULT 0,
    last_sent_at BIGINT,
    sent_day DATE,
    sent_count INT NOT NULL DEFAULT 0,
    failures INT NOT NULL DEFAULT 0,
    consumed BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_iam_otp_limits CHECK (sent_count BETWEEN 0 AND 5 AND failures BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
