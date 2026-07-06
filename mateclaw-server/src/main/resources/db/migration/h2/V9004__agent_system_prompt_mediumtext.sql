-- V9004: Allow long employee identity cards / system prompts.
-- H2 TEXT aliases to a character LOB in many modes, but make the intended large-text
-- storage explicit and align with MySQL MEDIUMTEXT semantics used in production.
ALTER TABLE mate_agent ALTER COLUMN system_prompt CLOB;
