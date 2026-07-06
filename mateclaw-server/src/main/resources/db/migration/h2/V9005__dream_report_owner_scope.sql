-- V9005: Scope dream reports to the same owner bucket as the memory they audit.
-- Without this, a personal Dream run can expose MEMORY.md diffs / topics to
-- every user that can open the agent's Memory timeline.
ALTER TABLE mate_dream_report ADD COLUMN IF NOT EXISTS owner_key VARCHAR(128) NULL;
ALTER TABLE mate_dream_report ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';

CREATE INDEX IF NOT EXISTS idx_dream_scope_owner
    ON mate_dream_report(agent_id, scope, owner_key, started_at DESC);
