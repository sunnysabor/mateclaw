-- V167: first-class ACP Agent runtime binding.
-- MySQL lacks ADD COLUMN IF NOT EXISTS, so guard through INFORMATION_SCHEMA.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'acp_endpoint_name'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN acp_endpoint_name VARCHAR(64) DEFAULT NULL',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
