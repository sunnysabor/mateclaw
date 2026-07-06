-- V9004: Allow long employee identity cards / system prompts.
-- MySQL TEXT is limited to 64 KiB, which is too small for long Role/Goal/Backstory prompts.
-- MEDIUMTEXT raises the ceiling to ~16 MiB while keeping the Java model unchanged.
ALTER TABLE mate_agent MODIFY COLUMN system_prompt MEDIUMTEXT NULL;
