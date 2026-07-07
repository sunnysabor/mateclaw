-- V9009: C-end user points account and immutable points ledger.

CREATE TABLE IF NOT EXISTS mate_points_account (
    id            BIGINT       NOT NULL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    balance       BIGINT       NOT NULL DEFAULT 0,
    total_earned  BIGINT       NOT NULL DEFAULT 0,
    total_spent   BIGINT       NOT NULL DEFAULT 0,
    level_code    VARCHAR(32)  NOT NULL DEFAULT 'normal',
    create_time   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_points_account_user ON mate_points_account (user_id);

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
    create_time    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_points_ledger_user_time ON mate_points_ledger (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_points_ledger_biz ON mate_points_ledger (biz_type, biz_id);
