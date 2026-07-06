-- V169: Allow long employee identity cards / system prompts.
-- KingbaseES/PostgreSQL TEXT is already large/unbounded for this use case,
-- so this migration is intentionally a no-op to keep Flyway versions aligned.
SELECT 1;
