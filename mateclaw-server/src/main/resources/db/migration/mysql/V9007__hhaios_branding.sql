-- V9007: refresh existing user-visible seed data for the HHAIOS brand.
-- Internal identifiers, API paths, env keys, class names, tool names and lowercase filesystem paths are intentionally untouched.

UPDATE mate_user
SET nickname = REPLACE(REPLACE(nickname, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE nickname LIKE '%Mate Claw%' OR nickname LIKE '%MateClaw%';

UPDATE mate_agent
SET name = REPLACE(REPLACE(name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    system_prompt = REPLACE(REPLACE(system_prompt, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE name LIKE '%Mate Claw%' OR name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%' OR system_prompt LIKE '%Mate Claw%' OR system_prompt LIKE '%MateClaw%';

UPDATE mate_agent_team
SET name = REPLACE(REPLACE(name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE name LIKE '%Mate Claw%' OR name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_agent_team_member
SET role_label = REPLACE(REPLACE(role_label, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE role_label LIKE '%Mate Claw%' OR role_label LIKE '%MateClaw%';

UPDATE mate_channel
SET name = REPLACE(REPLACE(name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE name LIKE '%Mate Claw%' OR name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_workspace
SET name = REPLACE(REPLACE(name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE name LIKE '%Mate Claw%' OR name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_model_config
SET name = REPLACE(REPLACE(name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE name LIKE '%Mate Claw%' OR name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_tool
SET display_name = REPLACE(REPLACE(display_name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE display_name LIKE '%Mate Claw%' OR display_name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_tool
SET display_name = 'HHAIOS Docs'
WHERE bean_name = 'mateClawDocTool'
  AND (display_name IS NULL OR display_name = '' OR display_name = 'MateClawDocTool' OR display_name LIKE '%MateClaw%');

UPDATE mate_skill
SET description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    author = REPLACE(REPLACE(author, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%' OR author LIKE '%Mate Claw%' OR author LIKE '%MateClaw%';

UPDATE mate_mcp_server
SET description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_system_setting
SET description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    setting_value = REPLACE(REPLACE(setting_value, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%' OR setting_value LIKE '%Mate Claw%' OR setting_value LIKE '%MateClaw%';

UPDATE mate_feature_flag
SET description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_plugin
SET display_name = REPLACE(REPLACE(display_name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    author = REPLACE(REPLACE(author, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE display_name LIKE '%Mate Claw%' OR display_name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%' OR author LIKE '%Mate Claw%' OR author LIKE '%MateClaw%';

UPDATE mate_acp_endpoint
SET display_name = REPLACE(REPLACE(display_name, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS'),
    description = REPLACE(REPLACE(description, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE display_name LIKE '%Mate Claw%' OR display_name LIKE '%MateClaw%' OR description LIKE '%Mate Claw%' OR description LIKE '%MateClaw%';

UPDATE mate_workspace_file
SET content = REPLACE(REPLACE(content, 'Mate Claw', 'HHAIOS'), 'MateClaw', 'HHAIOS')
WHERE agent_id IN (1000000001, 1000000002, 1000000003)
  AND (content LIKE '%Mate Claw%' OR content LIKE '%MateClaw%');
