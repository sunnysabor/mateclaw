-- V166: first-class managed coding agents for ACP.
--
-- Keep the older V68 rows intact for existing installs, but make the three
-- supported agents (Hermes / Codex / OpenClaw) present and idempotently
-- refreshed. Existing enabled flags and env_json are intentionally preserved.

UPDATE mate_acp_endpoint
   SET display_name = 'OpenAI Codex CLI',
       description = 'Delegate to Codex through the ACP adapter',
       builtin = TRUE,
       trusted = TRUE,
       args_json = CASE
           WHEN args_json = '["-y","@zed-industries/codex-acp"]'
               THEN '["-y","@agentclientprotocol/codex-acp"]'
           ELSE args_json
       END,
       update_time = NOW()
 WHERE name = 'codex';

INSERT INTO mate_acp_endpoint
    (id, name, display_name, description, command, args_json, env_json,
     tool_parse_mode, builtin, trusted, enabled,
     stdio_buffer_limit_bytes, workspace_id, create_time, update_time, deleted)
SELECT 9100001, 'codex', 'OpenAI Codex CLI', 'Delegate to Codex through the ACP adapter',
       'npx', '["-y","@agentclientprotocol/codex-acp"]', '{}',
       'call_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0
 WHERE NOT EXISTS (SELECT 1 FROM mate_acp_endpoint WHERE name = 'codex');

UPDATE mate_acp_endpoint
   SET display_name = 'Hermes Agent',
       description = 'Delegate to Hermes through its native ACP server',
       builtin = TRUE,
       trusted = TRUE,
       update_time = NOW()
 WHERE name = 'hermes';

INSERT INTO mate_acp_endpoint
    (id, name, display_name, description, command, args_json, env_json,
     tool_parse_mode, builtin, trusted, enabled,
     stdio_buffer_limit_bytes, workspace_id, create_time, update_time, deleted)
SELECT 9100005, 'hermes', 'Hermes Agent', 'Delegate to Hermes through its native ACP server',
       'hermes', '["acp","--accept-hooks"]', '{}',
       'update_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0
 WHERE NOT EXISTS (SELECT 1 FROM mate_acp_endpoint WHERE name = 'hermes');

UPDATE mate_acp_endpoint
   SET display_name = 'OpenClaw',
       description = 'Delegate to OpenClaw through its Gateway-backed ACP bridge',
       builtin = TRUE,
       trusted = TRUE,
       update_time = NOW()
 WHERE name = 'openclaw';

INSERT INTO mate_acp_endpoint
    (id, name, display_name, description, command, args_json, env_json,
     tool_parse_mode, builtin, trusted, enabled,
     stdio_buffer_limit_bytes, workspace_id, create_time, update_time, deleted)
SELECT 9100006, 'openclaw', 'OpenClaw', 'Delegate to OpenClaw through its Gateway-backed ACP bridge',
       'openclaw', '["acp"]', '{}',
       'update_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0
 WHERE NOT EXISTS (SELECT 1 FROM mate_acp_endpoint WHERE name = 'openclaw');
