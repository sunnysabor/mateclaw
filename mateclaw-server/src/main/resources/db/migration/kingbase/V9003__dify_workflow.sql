-- V9003: Dify workflow integration (global config + external run history).

CREATE TABLE IF NOT EXISTS mate_dify_workflow_config (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    config_key            VARCHAR(64)  NOT NULL DEFAULT 'global',
    name                  VARCHAR(128) NOT NULL,
    description           VARCHAR(1024),
    api_key_cipher        TEXT,
    input_schema_json     TEXT,
    default_inputs_json   TEXT,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    last_test_status      VARCHAR(32),
    last_test_error       VARCHAR(2048),
    last_test_at          TIMESTAMP(3),
    created_by            BIGINT,
    create_time           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dify_config_key
    ON mate_dify_workflow_config (config_key);

CREATE TABLE IF NOT EXISTS mate_external_workflow_run (
    id                     BIGINT       NOT NULL PRIMARY KEY,
    workspace_id           BIGINT       NOT NULL,
    provider               VARCHAR(32)  NOT NULL,
    config_id              BIGINT       NOT NULL,
    trigger_id             BIGINT,
    state                  VARCHAR(32)  NOT NULL,
    request_inputs_json    TEXT,
    response_outputs_json  TEXT,
    response_raw_json      TEXT,
    external_task_id       VARCHAR(128),
    external_run_id        VARCHAR(128),
    external_workflow_id   VARCHAR(128),
    error_code             VARCHAR(128),
    error_message          VARCHAR(2048),
    total_tokens           INT,
    total_steps            INT,
    elapsed_time_seconds   NUMERIC(12, 3),
    started_at             TIMESTAMP(3),
    completed_at           TIMESTAMP(3),
    triggered_by           VARCHAR(64),
    created_by             BIGINT,
    create_time            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ext_wf_run_workspace_created
    ON mate_external_workflow_run (workspace_id, create_time);
CREATE INDEX IF NOT EXISTS idx_ext_wf_run_config_created
    ON mate_external_workflow_run (config_id, create_time);
CREATE INDEX IF NOT EXISTS idx_ext_wf_run_external
    ON mate_external_workflow_run (provider, external_run_id);
