-- V9009: C-end user points account and immutable points ledger.

CREATE TABLE IF NOT EXISTS mate_points_account (
    id            BIGINT       NOT NULL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    balance       BIGINT       NOT NULL DEFAULT 0,
    total_earned  BIGINT       NOT NULL DEFAULT 0,
    total_spent   BIGINT       NOT NULL DEFAULT 0,
    level_code    VARCHAR(32)  NOT NULL DEFAULT 'normal',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_points_account_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C-end user points account.';

CREATE TABLE IF NOT EXISTS mate_points_ledger (
    id             BIGINT       NOT NULL PRIMARY KEY,
    account_id     BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    direction      VARCHAR(16)  NOT NULL,
    amount         BIGINT       NOT NULL,
    balance_after  BIGINT       NOT NULL,
    reason         VARCHAR(64)  NOT NULL,
    biz_type       VARCHAR(64),
    biz_id         VARCHAR(128),
    remark         VARCHAR(512),
    operator_id    BIGINT,
    create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_points_ledger_user_time (user_id, create_time),
    KEY idx_points_ledger_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable C-end user points ledger.';
