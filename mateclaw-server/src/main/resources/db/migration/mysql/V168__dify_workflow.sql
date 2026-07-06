-- V168: Dify workflow integration (global config + external run history).

CREATE TABLE IF NOT EXISTS mate_dify_workflow_config (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    config_key            VARCHAR(64)  NOT NULL DEFAULT 'global',
    name                  VARCHAR(128) NOT NULL,
    description           VARCHAR(1024),
    api_key_cipher        TEXT,
    input_schema_json     MEDIUMTEXT,
    default_inputs_json   MEDIUMTEXT,
    enabled               TINYINT      NOT NULL DEFAULT 1,
    last_test_status      VARCHAR(32),
    last_test_error       VARCHAR(2048),
    last_test_at          DATETIME(3),
    created_by            BIGINT,
    create_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted               INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_dify_config_key (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Global Dify workflow app configuration.';

CREATE TABLE IF NOT EXISTS mate_external_workflow_run (
    id                     BIGINT       NOT NULL PRIMARY KEY,
    workspace_id           BIGINT       NOT NULL,
    provider               VARCHAR(32)  NOT NULL,
    config_id              BIGINT       NOT NULL,
    trigger_id             BIGINT,
    state                  VARCHAR(32)  NOT NULL,
    request_inputs_json    MEDIUMTEXT,
    response_outputs_json  MEDIUMTEXT,
    response_raw_json      MEDIUMTEXT,
    external_task_id       VARCHAR(128),
    external_run_id        VARCHAR(128),
    external_workflow_id   VARCHAR(128),
    error_code             VARCHAR(128),
    error_message          VARCHAR(2048),
    total_tokens           INT,
    total_steps            INT,
    elapsed_time_seconds   DECIMAL(12, 3),
    started_at             DATETIME(3),
    completed_at           DATETIME(3),
    triggered_by           VARCHAR(64),
    created_by             BIGINT,
    create_time            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted                INT          NOT NULL DEFAULT 0,
    KEY idx_ext_wf_run_workspace_created (workspace_id, create_time),
    KEY idx_ext_wf_run_config_created (config_id, create_time),
    KEY idx_ext_wf_run_external (provider, external_run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'External workflow provider run history.';
