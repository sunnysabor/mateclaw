-- V9008: Local wallet / recharge center (mock payment only, no real gateway).

CREATE TABLE IF NOT EXISTS mate_billing_wallet (
    id             BIGINT      NOT NULL PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    balance_cents  BIGINT      NOT NULL DEFAULT 0,
    currency       VARCHAR(16) NOT NULL DEFAULT 'CNY',
    create_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_wallet_user ON mate_billing_wallet (user_id);

CREATE TABLE IF NOT EXISTS mate_billing_package (
    id             BIGINT       NOT NULL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    description    VARCHAR(512),
    amount_cents   BIGINT       NOT NULL,
    bonus_cents    BIGINT       NOT NULL DEFAULT 0,
    currency       VARCHAR(16)  NOT NULL DEFAULT 'CNY',
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order     INT          NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_billing_package_enabled ON mate_billing_package (enabled, sort_order);

CREATE TABLE IF NOT EXISTS mate_billing_order (
    id              BIGINT       NOT NULL PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    package_id      BIGINT,
    amount_cents    BIGINT       NOT NULL,
    bonus_cents     BIGINT       NOT NULL DEFAULT 0,
    currency        VARCHAR(16)  NOT NULL DEFAULT 'CNY',
    payment_method  VARCHAR(32)  NOT NULL DEFAULT 'mock',
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending',
    paid_at         TIMESTAMP,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_order_no ON mate_billing_order (order_no);
CREATE INDEX IF NOT EXISTS idx_billing_order_user_time ON mate_billing_order (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_billing_order_status ON mate_billing_order (status);

CREATE TABLE IF NOT EXISTS mate_billing_ledger (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    wallet_id            BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    order_id             BIGINT,
    direction            VARCHAR(16)  NOT NULL,
    amount_cents         BIGINT       NOT NULL,
    balance_after_cents  BIGINT       NOT NULL,
    reason               VARCHAR(64)  NOT NULL,
    remark               VARCHAR(512),
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_billing_ledger_user_time ON mate_billing_ledger (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_billing_ledger_order ON mate_billing_ledger (order_id);

MERGE INTO mate_billing_package (id, name, description, amount_cents, bonus_cents, currency, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (100000900801, '体验包', '本地模拟充值：¥10 到账 ¥10', 1000, 0, 'CNY', TRUE, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

MERGE INTO mate_billing_package (id, name, description, amount_cents, bonus_cents, currency, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (100000900802, '标准包', '本地模拟充值：¥50 到账 ¥55', 5000, 500, 'CNY', TRUE, 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

MERGE INTO mate_billing_package (id, name, description, amount_cents, bonus_cents, currency, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (100000900803, '专业包', '本地模拟充值：¥100 到账 ¥115', 10000, 1500, 'CNY', TRUE, 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
