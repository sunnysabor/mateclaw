-- V9002: first-class ACP Agent runtime binding.
-- ACP employees are normal mate_agent rows with agent_type='acp' and a managed
-- endpoint slug in acp_endpoint_name.

ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS acp_endpoint_name VARCHAR(64) DEFAULT NULL;
