-- V9005: Scope dream reports to the same owner bucket as the memory they audit.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_dream_report' AND column_name = 'owner_key'
    ) THEN
        ALTER TABLE mate_dream_report ADD COLUMN owner_key VARCHAR(128) NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_dream_report' AND column_name = 'scope'
    ) THEN
        ALTER TABLE mate_dream_report ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dream_scope_owner
    ON mate_dream_report (agent_id, scope, owner_key, started_at DESC);
