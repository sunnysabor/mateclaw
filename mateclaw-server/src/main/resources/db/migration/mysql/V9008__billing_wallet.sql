-- V9008: Local wallet / recharge center (mock payment only, no real gateway).

CREATE TABLE IF NOT EXISTS mate_billing_wallet (
    id             BIGINT      NOT NULL PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    balance_cents  BIGINT      NOT NULL DEFAULT 0,
    currency       VARCHAR(16) NOT NULL DEFAULT 'CNY',
    create_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_billing_wallet_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='User billing wallet balance.';

CREATE TABLE IF NOT EXISTS mate_billing_package (
    id             BIGINT       NOT NULL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    description    VARCHAR(512),
    amount_cents   BIGINT       NOT NULL,
    bonus_cents    BIGINT       NOT NULL DEFAULT 0,
    currency       VARCHAR(16)  NOT NULL DEFAULT 'CNY',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order     INT          NOT NULL DEFAULT 0,
    create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        INT          NOT NULL DEFAULT 0,
    KEY idx_billing_package_enabled (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Recharge packages.';

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
    paid_at         DATETIME(3),
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_billing_order_no (order_no),
    KEY idx_billing_order_user_time (user_id, create_time),
    KEY idx_billing_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Recharge orders.';

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
    create_time          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_billing_ledger_user_time (user_id, create_time),
    KEY idx_billing_ledger_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Wallet balance ledger.';

INSERT INTO mate_billing_package (id, name, description, amount_cents, bonus_cents, currency, enabled, sort_order, create_time, update_time, deleted)
VALUES
  (100000900801, '体验包', '本地模拟充值：¥10 到账 ¥10', 1000, 0, 'CNY', 1, 10, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0),
  (100000900802, '标准包', '本地模拟充值：¥50 到账 ¥55', 5000, 500, 'CNY', 1, 20, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0),
  (100000900803, '专业包', '本地模拟充值：¥100 到账 ¥115', 10000, 1500, 'CNY', 1, 30, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  description = VALUES(description),
  amount_cents = VALUES(amount_cents),
  bonus_cents = VALUES(bonus_cents),
  currency = VALUES(currency),
  enabled = VALUES(enabled),
  sort_order = VALUES(sort_order),
  update_time = CURRENT_TIMESTAMP(3),
  deleted = 0;
