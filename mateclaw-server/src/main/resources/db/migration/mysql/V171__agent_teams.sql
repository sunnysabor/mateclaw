-- V171: User-defined Agent teams. A team has one native HHAIOS
-- coordinator (react / plan_execute) and N member Agents (including ACP).

CREATE TABLE IF NOT EXISTS mate_agent_team (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    workspace_id          BIGINT       NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    description           MEDIUMTEXT,
    coordinator_agent_id  BIGINT       NOT NULL,
    enabled               TINYINT(1)   NOT NULL DEFAULT 1,
    create_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted               INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_agent_team_workspace_name (workspace_id, name, deleted),
    KEY idx_agent_team_coordinator (coordinator_agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='User-defined multi-agent teams.';

CREATE TABLE IF NOT EXISTS mate_agent_team_member (
    id           BIGINT       NOT NULL PRIMARY KEY,
    team_id      BIGINT       NOT NULL,
    agent_id     BIGINT       NOT NULL,
    role_label   VARCHAR(128),
    sort_order   INT          NOT NULL DEFAULT 0,
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted      INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_agent_team_member (team_id, agent_id, deleted),
    KEY idx_agent_team_member_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Members of a user-defined Agent team.';
