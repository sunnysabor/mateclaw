-- V9001: first-class managed coding agents for ACP.
--
-- Make Hermes / Codex / OpenClaw present without clobbering user-edited
-- command, args_json, env_json, or enabled flags on existing rows.
INSERT INTO mate_acp_endpoint
    (id, name, display_name, description, command, args_json, env_json,
     tool_parse_mode, builtin, trusted, enabled,
     stdio_buffer_limit_bytes, workspace_id, create_time, update_time, deleted)
VALUES
    (9100001, 'codex', 'OpenAI Codex CLI', 'Delegate to Codex through the ACP adapter',
     'npx', '["-y","@agentclientprotocol/codex-acp"]', '{}',
     'call_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0),
    (9100005, 'hermes', 'Hermes Agent', 'Delegate to Hermes through its native ACP server',
     'hermes', '["acp","--accept-hooks"]', '{}',
     'update_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0),
    (9100006, 'openclaw', 'OpenClaw', 'Delegate to OpenClaw through its Gateway-backed ACP bridge',
     'openclaw', '["acp"]', '{}',
     'update_detail', TRUE, TRUE, FALSE, 52428800, 1, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    description  = VALUES(description),
    args_json    = CASE
        WHEN mate_acp_endpoint.args_json = '["-y","@zed-industries/codex-acp"]'
            THEN VALUES(args_json)
        ELSE mate_acp_endpoint.args_json
    END,
    builtin      = VALUES(builtin),
    trusted      = VALUES(trusted),
    update_time  = NOW();
