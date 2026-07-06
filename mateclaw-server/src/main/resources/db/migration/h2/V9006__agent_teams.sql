-- V9006: User-defined Agent teams. A team has one native HHAIOS
-- coordinator (react / plan_execute) and N member Agents (including ACP).

CREATE TABLE IF NOT EXISTS mate_agent_team (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    workspace_id          BIGINT       NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    description           CLOB,
    coordinator_agent_id  BIGINT       NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_team_workspace_name
    ON mate_agent_team (workspace_id, name, deleted);
CREATE INDEX IF NOT EXISTS idx_agent_team_coordinator
    ON mate_agent_team (coordinator_agent_id);

CREATE TABLE IF NOT EXISTS mate_agent_team_member (
    id           BIGINT       NOT NULL PRIMARY KEY,
    team_id      BIGINT       NOT NULL,
    agent_id     BIGINT       NOT NULL,
    role_label   VARCHAR(128),
    sort_order   INT          NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_team_member
    ON mate_agent_team_member (team_id, agent_id, deleted);
CREATE INDEX IF NOT EXISTS idx_agent_team_member_agent
    ON mate_agent_team_member (agent_id);
