-- V9005: Scope dream reports to the same owner bucket as the memory they audit.
SET @db_name := DATABASE();

SET @has_owner_key := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = 'mate_dream_report' AND COLUMN_NAME = 'owner_key');
SET @ddl := IF(@has_owner_key = 0,
    'ALTER TABLE mate_dream_report ADD COLUMN owner_key VARCHAR(128) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_scope := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = 'mate_dream_report' AND COLUMN_NAME = 'scope');
SET @ddl := IF(@has_scope = 0,
    'ALTER TABLE mate_dream_report ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT ''TEAM''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = 'mate_dream_report' AND INDEX_NAME = 'idx_dream_scope_owner');
SET @ddl := IF(@has_idx = 0,
    'CREATE INDEX idx_dream_scope_owner ON mate_dream_report(agent_id, scope, owner_key, started_at DESC)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
