# Dify Workflow 集成需求澄清与技术方案

> 状态：v0.5，M1/M2 已实现；后端完整编译待 JDK 21 环境复验
> 日期：2026-07-02
> 目标：接入 Dify 官方 Workflow Service API，让 MateClaw 可以通过全局 Dify API Key 调用、触发并记录 Dify 工作流。

## 1. 背景

MateClaw 已有原生 Workflow/Trigger 能力，但当前设计是轻量线性 DSL：

- Workflow：线性 `steps[]` + 7 种 step mode，适合把 MateClaw 数字员工、审批、渠道分发、记忆写入串起来。
- Trigger：负责 cron/webhook/channel_message/workflow_completion 等事件治理和分发。
- 当前 trigger v0 只支持 `targetType=workflow`，还不能直接分发到外部工作流。

Dify 的优势是可视化 Workflow 编排、丰富节点、云端运行环境和现成 Service API。第一版不替换 MateClaw 原生 Workflow，也不做 Dify DSL 转换，而是把 Dify 作为一个外部 Workflow Provider 接入。

## 2. 已确认决策

根据当前产品决策，第一版按以下约束实现：

1. 只允许调用 Dify 官方云：`https://api.dify.ai/v1`，不在 M1 开放自定义 `baseUrl`。
2. Dify API Key 为全局共享，整个项目只配置一个 Key。
3. 运行记录保存完整 `inputs` 和完整 `outputs`。
4. Dify Key 配置、测试运行、运行记录查看入口只允许 global admin。
5. 指向 Dify 的 trigger 使用全局 Key，因此 `targetType=dify_workflow` 的创建、修改、启停和删除都只允许 global admin。
6. 第一版 Dify outputs 只保存到运行记录，不自动投递到聊天或渠道消息。

重要说明：Dify Workflow Service API 的 API Key 通常绑定到具体 Dify App。因此“全项目一个 API Key”在 M1 等价于“全项目接入一个全局 Dify Workflow App”。后续如果要同时接多个 Dify Workflow App，需要再升级为 App 级 Key 或多配置模型。

## 3. 第一版目标

第一版目标是“官方 Dify API 调用 + MateClaw 触发器启动 + 运行记录”。第一版按 M1 + M2 交付，实际开发先落 M1 手动运行，再落 M2 Trigger 集成。

必须支持：

1. global admin 在 MateClaw 中配置全局 Dify Workflow：
   - 名称
   - Dify 官方云 Base URL 固定为 `https://api.dify.ai/v1`
   - 全局 Dify API Key，后端加密存储，前端不可见
   - 可选输入说明/示例 JSON
   - 启用/禁用
2. 后端手动运行一个 Dify workflow：
   - 调用 `POST /workflows/run`
   - 第一版默认使用 `response_mode=blocking`
   - `inputs` 由调用方传入
   - `user` 由 MateClaw 后端生成稳定值
3. 保存外部运行记录：
   - Dify `task_id`
   - Dify `workflow_run_id`
   - 状态、完整输入、完整输出、错误、token、耗时
   - 第一版只保存 outputs，不自动回写或投递到聊天/渠道消息
4. M2 扩展 MateClaw Trigger：
   - 新增 `targetType=dify_workflow`
   - trigger 的 `payloadTemplate` 渲染结果作为 Dify `inputs`
   - 复用现有 dedup、rate limit、bot self filter、cron 调度
5. 前端提供基础管理和运行历史：
   - 全局 Dify Workflow 配置
   - API key 配置/更新
   - 启用/禁用
   - 测试连接/测试运行
   - 查看运行记录

## 4. 明确不做

第一版不做：

- 不内嵌 Dify 控制台/设计器。
- 不 iframe 嵌入 Dify Workflow WebApp。
- 不把 Dify workflow DSL 转换为 MateClaw 原生 workflow JSON。
- 不替换 MateClaw 原生 Workflow。
- 不支持 Dify Human Input。
- 不支持 Dify 文件上传。
- 不支持 streaming SSE 事件代理。
- 不让 Dify 反向调用 MateClaw agent/tool/wiki。
- 不支持多个 Dify App 或多个 Dify API Key。
- 不支持自定义 Dify Base URL。

这些能力可以放到后续里程碑。

## 5. Dify API 依赖

以 Dify Workflow App Service API 为准。

官方文档：

- Run Workflow: https://docs.dify.ai/api-reference/workflows/run-workflow.md
- Get Workflow Run Detail: https://docs.dify.ai/api-reference/workflows/get-workflow-run-detail.md
- Human Input Form: https://docs.dify.ai/api-reference/human-input/get-human-input-form.md
- Submit Human Input Form: https://docs.dify.ai/api-reference/human-input/submit-human-input-form.md

第一版只依赖：

```http
POST https://api.dify.ai/v1/workflows/run
Authorization: Bearer {apiKey}
Content-Type: application/json
```

请求体：

```json
{
  "inputs": {
    "query": "example"
  },
  "response_mode": "blocking",
  "user": "ws-100:user-200"
}
```

典型响应：

```json
{
  "task_id": "c3800678-a077-43df-a102-53f23ed20b88",
  "workflow_run_id": "fb47b2e6-5e43-4f90-be01-d5c5a088d156",
  "data": {
    "id": "fb47b2e6-5e43-4f90-be01-d5c5a088d156",
    "workflow_id": "7c3e33d4-2a8b-4e5f-9b1a-d3c6e8f12345",
    "status": "succeeded",
    "outputs": {
      "result": "..."
    },
    "error": null,
    "elapsed_time": 1.23,
    "total_tokens": 150,
    "total_steps": 3,
    "created_at": 1705407629,
    "finished_at": 1705407630
  }
}
```

## 6. 用户与权限模型

### 6.1 MateClaw 侧权限

第一版按全局能力管理，不沿用 workspace admin：

- 配置 Dify API Key：必须是 global admin，接口使用项目已有 `@RequireGlobalAdmin`。
- 手动测试运行：必须是 global admin。
- 查看运行记录：必须是 global admin。
- 普通 trigger 创建/修改仍沿用现有 trigger 权限。
- `targetType=dify_workflow` 的 trigger 创建、修改、启停和删除必须是 global admin。
- Trigger 自动运行：运行时不要求触发事件携带 global admin 用户上下文，但必须引用已启用的全局 Dify 配置。

### 6.2 Dify user 字段

Dify `user` 必填，并影响 Dify 侧运行和文件可见性。第一版生成规则：

```text
ws-{workspaceId}:user-{userId}
```

触发器自动运行时没有明确用户上下文，使用：

```text
ws-{workspaceId}:trigger-{triggerId}
```

后续如果需要按最终业务用户隔离，可以在 trigger payload 中允许显式指定 `difyUser`，但默认不开放，避免用户伪造。

## 7. 数据模型

### 7.1 全局 Dify Workflow 配置表

建议新增 `mate_dify_workflow_config`。第一版只保留一条全局配置记录，后续如果要支持多个 Dify Workflow App，可以扩展为多行或迁移到 App 级配置。

```sql
CREATE TABLE mate_dify_workflow_config (
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
    update_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted               INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_dify_config_key (config_key)
);
```

字段说明：

- `config_key`：固定为 `global`，保证全项目只有一条全局配置。
- `name`：全局 Dify Workflow 显示名。
- `api_key_cipher`：加密后的全局 Dify API Key，允许为空；运行时必须校验已配置。
- `input_schema_json`：人工维护的输入说明或示例 schema，不强依赖 Dify introspection。
- `default_inputs_json`：测试运行默认参数。
- `enabled`：全局启停开关。

实现约束：

- `base_url` 不落库，代码常量固定为 `https://api.dify.ai/v1`。
- API Key 创建/更新只允许 global admin。
- 返回前端时不返回 `api_key_cipher`，只返回 `apiKeyConfigured: true/false`。
- 配置不做软删除，禁用时只写 `enabled=false`。
- `deleted` 字段保留是为了贴合项目表结构习惯，M1 不提供删除入口，正常值固定为 `0`。

迁移文件：

- 需要分别新增 H2 / MySQL / Kingbase 三套 Flyway migration。
- 文件位置：
  - `mateclaw-server/src/main/resources/db/migration/h2/Vxxx__dify_workflow.sql`
  - `mateclaw-server/src/main/resources/db/migration/mysql/Vxxx__dify_workflow.sql`
  - `mateclaw-server/src/main/resources/db/migration/kingbase/Vxxx__dify_workflow.sql`
- `Vxxx` 使用当时仓库未占用的下一 Flyway 版本号，三种数据库保持同一个版本号。
- 不复用已有版本号；如果并行分支已经占用该版本，开发时需要顺延。

方言映射：

| 字段语义 | MySQL | H2 | Kingbase |
|---|---|---|---|
| 长 JSON 文本 | `MEDIUMTEXT` | `CLOB` 或 `TEXT` | `TEXT` |
| 布尔/启停 | `TINYINT` | `BOOLEAN` 或 `TINYINT` | `SMALLINT` 或 `BOOLEAN` |
| 时间 | `DATETIME(3)` | `TIMESTAMP` | `TIMESTAMP(3)` |
| 小数耗时 | `DECIMAL(12,3)` | `DECIMAL(12,3)` | `NUMERIC(12,3)` |
| 普通索引 | `KEY idx_name (...)` | `CREATE INDEX IF NOT EXISTS ...` | `CREATE INDEX IF NOT EXISTS ...` |

### 7.2 外部运行记录表

建议新增 `mate_external_workflow_run`。

```sql
CREATE TABLE mate_external_workflow_run (
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
);
```

状态映射：

| Dify status | MateClaw state |
|---|---|
| `succeeded` | `succeeded` |
| `failed` | `failed` |
| `stopped` | `cancelled` |
| `partial-succeeded` | `partial_succeeded` |
| `paused` | `paused` |
| request/network error | `failed` |

## 8. 后端模块设计

新增包：

```text
vip.mate.dify
  api/
  model/
  repository/
  service/
  client/
```

### 8.1 Client

新增 `DifyWorkflowClient`。

职责：

- 构造 Dify HTTP 请求。
- 注入 `Authorization: Bearer {apiKey}`。
- 固定调用 `https://api.dify.ai/v1`。
- 默认 `response_mode=blocking`。
- 解析成功响应。
- 将 Dify 错误响应映射为 MateClaw 可读异常。
- 设置连接/读取超时。
- 不记录 `Authorization` header，不在异常信息中拼接 API key。

建议超时：

- connect timeout：5s
- read timeout：95s

说明：Dify blocking 模式可能遇到网关超时；官方云存在约 100s 网关限制，M1 将读取超时设为 95s，避免请求长时间悬挂。第一版只适合短工作流，后续 streaming 或异步轮询可规避长任务超时。

### 8.2 Service

新增 `DifyWorkflowService`。

核心方法：

```java
public DifyWorkflowConfigEntity getConfig();
public DifyWorkflowConfigEntity saveConfig(...);
public void disableConfig();
public DifyWorkflowRunEntity run(long workspaceId, RunRequest request);
public DifyWorkflowRunEntity testRun(long workspaceId, Map<String, Object> inputs);
public DifyWorkflowRunEntity runFromTrigger(TriggerEntity trigger, Map<String, Object> inputs);
```

运行流程：

1. 获取全局 Dify workflow config。
2. 校验 config enabled 且已配置 API Key。
3. 解密 API key。
4. 生成 Dify `user`。
5. 插入 `mate_external_workflow_run`，state=`running`。
6. 调用 Dify `/workflows/run`。
7. 更新 run 行：
   - 成功：state、task id、run id、outputs、tokens、elapsed time。
   - 失败：state=`failed`、error code、error message、raw response。
8. 返回 run。

workspace 来源：

- 手动运行和测试运行从当前请求头 `X-Workspace-Id` 读取 `workspaceId`，没有 workspace 时拒绝运行。
- Trigger 运行使用 `trigger.workspaceId`。
- 运行记录的 `workspace_id` 必须落对应 workspace，不能写空值或全局虚拟值。

### 8.3 API

新增 `DifyWorkflowController`。

建议路径：

```text
GET    /api/v1/dify/workflow/config
PUT    /api/v1/dify/workflow/config
POST   /api/v1/dify/workflow/test
POST   /api/v1/dify/workflow/run
GET    /api/v1/dify/workflow/runs?limit=50
GET    /api/v1/dify/runs/{runId}
```

注意：

- 所有接口第一版都使用 `@RequireGlobalAdmin`。
- 返回配置时不返回 `api_key_cipher`，只返回是否已配置。
- 更新时如果 `apiKey` 为空，不覆盖旧密钥。
- 禁用配置使用 `enabled=false`，不建议物理删除。
- `POST /test` 和 `POST /run` 使用 `@RequestHeader("X-Workspace-Id") Long workspaceId` 作为运行记录的 `workspaceId`；缺少该 header 必须拒绝执行，不能回退默认 workspace。
- 如果请求体传入 `baseUrl`，后端应忽略或拒绝；M1 不接受自定义调用地址。
- 返回值沿用项目现有 `R<T>` 包装。
- 业务异常沿用 `MateClawException`，新增错误 key 建议使用 `err.dify.*` 前缀并写入 `messages.properties`。

查询规则：

- `GET /api/v1/dify/workflow/runs?limit=50` 按 `create_time DESC` 返回。
- `limit` 默认 50，最大 200；超过最大值按 200 处理。
- `GET /api/v1/dify/runs/{runId}` 只允许 global admin 访问。
- run 不存在、已删除或不属于当前可见范围时返回 404。
- 运行记录详情返回完整 `requestInputs`、`responseOutputs` 和允许保存的 `responseRaw`。

DTO 契约：

```ts
type DifyWorkflowConfigVO = {
  id: string
  name: string
  description?: string
  baseUrl: 'https://api.dify.ai/v1'
  enabled: boolean
  apiKeyConfigured: boolean
  inputSchemaJson?: string
  defaultInputsJson?: string
  lastTestStatus?: string
  lastTestError?: string
  lastTestAt?: string
  updateTime?: string
}

type SaveDifyWorkflowConfigRequest = {
  name: string
  description?: string
  apiKey?: string
  enabled: boolean
  inputSchemaJson?: string
  defaultInputsJson?: string
}

type RunDifyWorkflowRequest = {
  inputs: Record<string, unknown>
}

type DifyWorkflowRunVO = {
  id: string
  workspaceId: string
  state: string
  requestInputs: Record<string, unknown>
  responseOutputs?: Record<string, unknown>
  responseRaw?: unknown
  externalTaskId?: string
  externalRunId?: string
  externalWorkflowId?: string
  errorCode?: string
  errorMessage?: string
  totalTokens?: number
  totalSteps?: number
  elapsedTimeSeconds?: number
  triggeredBy?: string
  createdBy?: string
  createTime: string
  completedAt?: string
}
```

请求约束：

- `inputs` 必须是 JSON object，不能是数组、字符串或 null。
- 测试运行如果未传 `inputs`，前端可使用 `defaultInputsJson` 作为默认值；后端仍按最终请求体执行。
- `apiKey` 为空字符串、null 或字段缺失时都表示“不覆盖旧密钥”。

## 9. Trigger 集成

### 9.1 当前限制

当前 `TriggerService` 的 `SUPPORTED_TARGETS` 只允许 `workflow`。`TriggerDispatcher` 也只处理 `"workflow"`。

### 9.2 改造目标

新增目标：

```text
targetType = "dify_workflow"
targetId = mate_dify_workflow_config.id
```

虽然 M1 只有一个全局配置，但 `mate_trigger.target_id` 当前是 NOT NULL，因此 trigger 仍指向全局配置行 id，避免改动现有 trigger 表结构。

权限要求：

- 创建、修改、启停或删除 `targetType=dify_workflow` 的 trigger 必须是 global admin。
- 普通 `targetType=workflow` trigger 保持现有权限逻辑。
- 权限落点固定在 `TriggerController`：读取当前用户，如果请求会创建、修改、启停或删除 `targetType=dify_workflow` 且用户不是 global admin，直接拒绝请求。更新、启停和删除时需要先读取已有 trigger，避免非 global admin 通过只改 `enabled` 或删除接口绕过限制。
- `TriggerService` 仍负责 target shape、targetId 和配置存在性校验，避免绕过 controller 的内部调用写入非法 target。

### 9.3 设计方式

引入分发 SPI，避免 `TriggerDispatcher` 继续写成 if/else。

```java
public interface TriggerTargetDispatcher {
    String targetType();
    DispatchResult dispatch(TriggerEntity trigger, Map<String, Object> event);
}
```

实现：

- `LocalWorkflowTriggerTargetDispatcher`：承接现有 workflow 分发逻辑。
- `DifyWorkflowTriggerTargetDispatcher`：调用 `DifyWorkflowService.runFromTrigger(...)`。

`TriggerDispatcher` 改为 registry：

```java
TriggerTargetDispatcher dispatcher = registry.get(trigger.getTargetType());
if (dispatcher == null) return DispatchResult.skipped("unsupported target_type");
return dispatcher.dispatch(trigger, event);
```

### 9.4 Payload 渲染

复用当前 `payloadTemplate` 渲染逻辑：

- 空 `payloadTemplate`：把事件 `data` 原样作为 Dify `inputs`。
- 非空：Pebble 渲染后必须是 JSON object，否则 dispatch failed。

示例 trigger：

```json
{
  "name": "daily-dify-report",
  "patternType": "cron",
  "patternJson": {
    "cron": "0 0 9 * * *",
    "timezone": "Asia/Shanghai"
  },
  "targetType": "dify_workflow",
  "targetId": 100001,
  "payloadTemplate": "{ \"date\": \"{{ now | date('yyyy-MM-dd') }}\" }",
  "rateLimitPerMin": 10,
  "dedupWindowSecs": 60,
  "enabled": true
}
```

## 10. 前端改造

### 10.1 新增页面

建议新增：

```text
mateclaw-ui/src/views/DifyWorkflows.vue
```

路由：

```text
/settings/dify-workflows
```

页面功能：

- 全局 Dify Workflow 配置表单
- API key 配置/更新
- 启用/禁用
- 默认 inputs JSON 编辑
- 测试运行
- 最近运行记录
- 仅 global admin 可见
- 页面必须提示：Dify inputs 会发送到 Dify 官方云，inputs/outputs 会完整保存到 MateClaw 数据库，敏感数据需谨慎提交。

导航入口：

- 放在现有设置/系统管理类菜单下。
- 前端 nav item 标记 `globalAdmin: true`，与现有全局管理员菜单保持一致。
- 非 global admin 不展示入口；直接访问路由时也应被路由守卫拒绝。

### 10.2 Trigger UI

改造 `Scheduler/TriggersPanel.vue`：

- `targetType` 下拉从固定 workflow 改为：
  - `workflow`
  - `dify_workflow`
- 当 targetType=`workflow` 时，targetId 列表取已发布 MateClaw workflow。
- 当 targetType=`dify_workflow` 时，targetId 固定为全局 Dify config id，显示全局配置名。
- 卡片显示目标名时兼容 Dify workflow。
- 非 global admin 不展示 `dify_workflow` 选项；后端仍必须做权限校验。

### 10.3 API 封装

新增 `difyWorkflowApi`：

```ts
export const difyWorkflowApi = {
  getConfig: () => http.get('/dify/workflow/config'),
  saveConfig: (data: SaveDifyWorkflowConfigRequest) => http.put('/dify/workflow/config', data),
  test: (inputs: Record<string, unknown>) => http.post('/dify/workflow/test', { inputs }),
  run: (inputs: Record<string, unknown>) => http.post('/dify/workflow/run', { inputs }),
  runs: (limit = 50) => http.get('/dify/workflow/runs', { params: { limit } }),
  getRun: (runId: number | string) => http.get(`/dify/runs/${runId}`),
}
```

## 11. 安全与合规

### 11.1 API Key

- Dify API key 只允许服务端保存。
- 前端永远不返回明文 key。
- 创建/更新时前端提交明文 key，后端立即加密存储。
- 只有 global admin 可以配置或轮换 key。
- 后续编辑时展示 `configured: true`，不展示密钥。

加密实现：

- M1 复用项目已有的 Hutool AES 加密风格，使用 `mateclaw.datasource.encrypt-key` 派生 16 字节 AES key。
- 不新增 `mateclaw.dify.encrypt-key`，避免运维同时维护多把本地密钥。
- 加密/解密逻辑建议抽成 Dify 模块内的小型 helper；后续如果项目抽统一 SecretService，再迁移复用。
- 解密失败时不能把密文或明文写入日志，只记录配置 id 和失败原因摘要。

### 11.2 数据外发

调用官方 Dify 云意味着 `inputs` 会发送到 Dify 官方服务。需要在产品/部署文档中明确：

- 不要把敏感客户数据发送到未经审批的 Dify App。
- 触发器 payload 可能来自 IM/webhook，进入 Dify 前不会自动脱敏。
- 第一版运行记录会保存完整 `inputs` 和完整 `outputs`，数据库访问权限需要按敏感数据处理。
- 如果业务要求数据不出域，应使用自托管 Dify，或不启用该集成。
- `response_raw_json` 可以保存 Dify 原始响应，但写入前必须确认不包含 API key、Authorization header 或其它服务端密钥。
- 服务端日志不得打印 Dify 请求 headers、完整 inputs、完整 outputs 或 raw response；需要排障时只记录 run id、状态、错误码和截断后的错误摘要。

### 11.3 SSRF

第一版固定官方云 `https://api.dify.ai/v1`，不接受用户输入的 `baseUrl`，因此不引入新的 SSRF 面。后续如果开放自定义 `baseUrl`：

- 需要校验 URL scheme 仅允许 HTTPS，开发环境可配置允许 HTTP。
- 需要接入现有 SSRF guard。
- 访问内网自托管 Dify 时，需要管理员配置窄 allowlist。

### 11.4 审计

第一版最低审计：

- 配置创建/更新/删除记录操作人。
- 每次运行记录 `workspaceId`、`configId`、`triggerId`、`createdBy`、`triggeredBy`。
- 错误信息不要记录 API key。

后续可接入统一 audit event。

## 12. 错误处理

Dify 常见错误映射：

| Dify/HTTP | MateClaw 表现 |
|---|---|
| 400 `not_workflow_app` | 配置错误：API key 对应 App 不是 workflow |
| 400 `provider_not_initialize` | Dify App 模型供应商未配置 |
| 400 `provider_quota_exceeded` | Dify 或上游模型额度不足 |
| 400 `invalid_param` | inputs/user 参数错误 |
| 429 | Dify 限流，记录 failed，提示稍后重试 |
| 500 | Dify 服务异常 |
| timeout | 调用超时，记录 failed |
| network error | 网络不可达，记录 failed |

错误响应统一保留：

- `error_code`
- `error_message`
- `response_raw_json`，注意不得包含密钥。

## 13. 里程碑

### 13.0 排期与推进规则

总体建议按 1.5 周安排 M1 + M2，预留 Dify API 实测、三套 Flyway 方言、权限测试和前端细节缓冲。

分阶段排期：

- M1 手动运行可用：3-5 个工作日；如果只做“全局配置 + 手动测试运行 + 运行记录”最小闭环，最快约 3 个工作日。
- M2 Trigger 集成：3-4 个工作日。
- M1 + M2 完整落地：6-9 个工作日。
- M3 生产加固：1-2 周，按实际上线要求拆分。

持续交付规则：

- 每完成一个里程碑，必须回写本文档，更新状态、完成项、偏差、遗留风险和下一里程碑入口条件。
- 如果实现中出现和本文档不一致的设计取舍，先更新本文档并说明原因，再继续实现。
- 如果并行分支占用了 Flyway 版本号，按实际仓库最新版本顺延，并在本文档记录最终版本号。
- 后续接续开发时，以本文档的“已确认问题”和最新里程碑状态作为上下文，不要求重新澄清 M1/M2 已确认范围。

### M1：手动运行可用

预计 3-5 个工作日。

当前状态：已实现，待 JDK 21 环境下完成后端编译/测试复验。

实际落地：

- Flyway 版本使用 `V168__dify_workflow.sql`，已覆盖 MySQL / H2 / Kingbase。
- 后端新增 `vip.mate.dify` 模块，包含 config/run 实体、mapper、controller、service、client 和密钥加密 helper。
- `DifyWorkflowClient` 固定调用 `https://api.dify.ai/v1/workflows/run`，blocking 模式，connect timeout 5s，read timeout 95s。
- 配置接口、测试运行、手动运行、运行记录列表和详情均使用 `@RequireGlobalAdmin`。
- `POST /workflow/test` 和 `POST /workflow/run` 显式要求 `X-Workspace-Id`，不使用拦截器默认 workspace。
- 配置保存时 API Key 只写不读；返回前端只暴露 `apiKeyConfigured`。
- 运行前插入 `mate_external_workflow_run`，成功/失败都会更新运行记录，完整保存 inputs、outputs 和 raw response。
- 前端新增 `Settings/DifyWorkflows/index.vue`，路由为 `/settings/dify-workflows`，入口只对 global admin 展示。
- Dify 页面已展示数据外发到 Dify 官方云、完整 inputs/outputs/raw 落库的风险提示。
- 前端 Dify test/run 使用 110s axios timeout，避免被全局 30s timeout 提前截断。

偏差说明：

- 文档早期建议页面文件为 `mateclaw-ui/src/views/DifyWorkflows.vue`；实际按现有 Settings 目录结构落在 `mateclaw-ui/src/views/Settings/DifyWorkflows/index.vue`。

交付：

- 数据库迁移
- 全局 Dify workflow config 读写
- Dify blocking run
- 运行记录
- 前端基础管理页
- 测试运行按钮

验收：

- global admin 可以配置全局 Dify API key。
- 能用示例 inputs 跑通 Dify workflow。
- 成功/失败都能在运行记录里看到完整 inputs/outputs。
- 非 global admin 无法访问 Dify 配置、测试运行和运行记录接口。
- 没有 `X-Workspace-Id` 请求头时，测试运行和手动运行拒绝执行。
- runs 列表按创建时间倒序返回，`limit` 超过 200 时被截断为 200。
- API key 不出现在任何响应体、错误信息或服务端日志中。

### M2：Trigger 集成

预计 3-4 个工作日。

当前状态：已实现，待 JDK 21 环境下完成后端编译/测试复验。

实际落地：

- 新增 `TriggerTargetDispatcher` SPI，`TriggerDispatcher` 改为 targetType registry。
- 原 workflow 分发逻辑迁移到 `LocalWorkflowTriggerTargetDispatcher`。
- 新增 `DifyWorkflowTriggerTargetDispatcher`，触发器运行时调用 `DifyWorkflowService.runFromTrigger(...)`。
- 新增 `TriggerPayloadRenderer` 复用现有 Pebble 渲染逻辑：空模板使用 raw event，非空模板必须渲染为 JSON object。
- `TriggerService.SUPPORTED_TARGETS` 已支持 `workflow` 和 `dify_workflow`。
- `TriggerService` 会校验 `dify_workflow.targetId` 指向 `config_key='global'` 的 Dify config 行。
- `DifyWorkflowService.runFromTrigger` 运行前再次校验 trigger targetId 等于当前全局 config id。
- `TriggerController` 对 `dify_workflow` 创建、更新、启停和删除增加 global admin 校验；更新时同时检查 existing trigger 与请求体，防止通过 toggle 或改 targetType 绕过。
- Trigger UI 支持 global admin 选择 `targetType=dify_workflow`，targetId 固定绑定全局 Dify config。
- 非 global admin 不展示 Dify 目标选项；已有 Dify trigger 的编辑、启停和删除按钮禁用。
- Trigger 更新保留 `fireCount`、`lastFiredAt`、`lastDispatchedAt` 和 `lastError` 运行态字段。

交付：

- trigger target dispatcher SPI
- `targetType=dify_workflow`
- Trigger UI 支持 Dify target
- cron/webhook/channel_message 可触发 Dify workflow

验收：

- 非 global admin 不能创建、修改、启停或删除 `targetType=dify_workflow` trigger。
- 创建 cron trigger 指向全局 Dify workflow，到点后产生外部运行记录。
- webhook trigger 能把 payloadTemplate 渲染为 Dify inputs。
- unsupported target fail-closed。

### M3：生产加固

预计 1-2 周。

交付：

- API key 轮换处理
- 审计事件
- 更完整错误展示
- 后端单元测试/集成测试
- 前端表单校验
- 文档

验收：

- API key 不出现在任何前端响应和日志。
- Dify 调用地址固定为官方云，不接受前端传入 baseUrl。
- 触发器失败会写 lastError。

### M4：后续能力

后续按需做：

- streaming SSE 代理
- Human Input 表单代理
- 文件上传映射
- Dify WebApp iframe
- MateClaw 原生 workflow 增加 `invoke_dify` step
- Dify 反向调用 MateClaw agent/tool/wiki
- 多 Dify Workflow App / 多 API Key
- 自定义 Dify Base URL / 自托管 Dify

## 14. 测试计划

后端测试：

- `DifyWorkflowServiceTest`
  - saveConfig/getConfig 不返回明文 key
  - 只有 global admin 可访问 controller
  - API key 使用 `mateclaw.datasource.encrypt-key` 加密保存
  - apiKey 为空时不覆盖旧密钥
  - 未配置 apiKey 时 run/testRun 拒绝执行
  - 缺少 `X-Workspace-Id` 时 run/testRun 拒绝执行
  - inputs 非 JSON object 时拒绝执行
  - run 成功写运行记录
  - run 失败写错误记录
  - disabled config 不允许运行
  - 完整 inputs/outputs 写入运行记录
  - run list 按 `create_time DESC` 且 limit 最大 200
  - raw response 和日志不包含 API key / Authorization
- `DifyWorkflowClientTest`
  - 成功响应解析
  - 400/429/500 映射
  - timeout 映射
- `TriggerDispatcherTest`
  - workflow target 保持兼容
  - dify_workflow target 可分发
  - unsupported target fail-closed
  - payloadTemplate 非 JSON object 时失败
- `TriggerServiceTest`
  - targetType 支持列表包含 `dify_workflow`
  - dify_workflow targetId 指向全局 config 行
  - 非 global admin 不能创建、修改、启停或删除 dify_workflow trigger

前端测试：

- Dify workflow 全局配置页渲染。
- 配置表单校验。
- API key 字段不回显。
- Trigger targetType 切换后 targetId 正确绑定全局 Dify config。
- 非 global admin 不展示 Dify workflow 配置页和 trigger 选项。
- Dify workflow 配置页展示数据外发和完整落库提示。
- 设置菜单入口带 `globalAdmin: true`，直接访问路由会被守卫拒绝。

手工验收：

1. 用 global admin 配置全局 Dify API Key。
2. 点击测试运行，看到 succeeded 记录。
3. 使用错误 API key，看到 failed 记录和清晰错误。
4. 创建 cron trigger 指向 Dify workflow，触发后看到 run。
5. 创建 webhook trigger 指向 Dify workflow，POST `/api/v1/triggers/events` 后看到 run。
6. 用非 global admin 访问 Dify 配置接口，确认返回无权限。
7. 用非 global admin 创建 `dify_workflow` trigger，确认返回无权限。
8. 检查服务端日志，确认没有 Dify API key、Authorization header、完整 inputs/outputs。

## 15. 已确认问题

以下问题已由产品确认，并已固化到 M1 设计：

1. 第一版只允许官方云 `https://api.dify.ai/v1`。
2. Dify API Key 全局共享，整个项目只配置一个 Key。
3. 运行记录允许保存完整 `inputs` 和完整 `outputs`。
4. Dify Key 配置权限仅限 global admin。
5. `targetType=dify_workflow` trigger 创建、修改、启停和删除权限仅限 global admin。
6. 第一版 Dify outputs 只保存到运行记录，不自动投递到聊天或渠道。
7. Dify blocking 调用 read timeout 使用 95s。
8. Dify 管理页需要展示数据外发和完整落库提示。

## 15.1 当前实施状态（2026-07-02）

已完成范围：

- M1 后端：数据库迁移、全局配置、密钥加密存储、blocking 调用、运行记录、运行详情、权限控制。
- M1 前端：Dify 配置页、Key 轮换输入、启停、JSON 输入、测试运行、手动运行、运行记录详情、风险提示。
- M2 后端：Trigger dispatcher SPI、`dify_workflow` target、payload 渲染、全局配置 target 校验、global admin 权限。
- M2 前端：Trigger targetType 下拉支持 `dify_workflow`，targetId 自动绑定全局 Dify config，非 global admin 隐藏/禁用相关入口。

已验证：

- `./node_modules/.bin/vue-tsc --noEmit`：通过。
- `./node_modules/.bin/vite build`：通过。
- `pnpm build`：失败，原因是现有脚本引用的 `../scripts/check-snowflake-precision.sh` 在当前仓库路径下不存在；绕过该缺失脚本后的类型检查和 Vite 构建均通过。
- `mvn -pl mateclaw-server -DskipTests compile`：失败，原因是本机 Java 为 17，而项目 `pom.xml` 要求 `maven.compiler.release=21`，报错 `不支持发行版本 21`。需在 JDK 21 环境复跑后端编译/测试。

待复验：

- 在 JDK 21 环境运行 `mvn -pl mateclaw-server -DskipTests compile`。
- 接真实 Dify Workflow App API Key 做手动 test/run。
- 用 cron/webhook trigger 各做一次 `dify_workflow` 端到端触发验收。

当前无继续编码前必须确认的问题。

## 16. 后续待确认问题

这些问题不阻塞 M1/M2 主链路，但会影响后续增强：

1. Dify workflow 的失败是否要触发 MateClaw 的 `workflow_completion` 类事件，支持后续链路？
2. 是否需要在 M1 就加入统一 audit log，还是 M3 再做？
3. 是否计划短期接 Human Input？如果需要，应提前在 run 表里预留 form token 和 pause 信息。
4. 是否需要在后续支持多个 Dify App / 多 API Key / 自托管 Dify？

## 17. 初步建议

建议按以下范围启动开发：

- M1 固定官方云 URL，不保存也不接受 `baseUrl`。
- M1 只实现全局单例 Dify Workflow 配置和全局唯一 API Key。
- M1 提供手动测试运行入口，便于验证配置。
- M1 保存完整 inputs/outputs，并在 UI 提示数据会外发到 Dify 且会落库保存。
- M1 不把 Dify outputs 自动投递到聊天或渠道，先只保存在运行记录。
- M2 再接 trigger，并限制 `dify_workflow` trigger 只能由 global admin 创建、修改、启停或删除。
- M3 再做 streaming、Human Input、文件上传、audit 深化、多 Dify App 和自定义 Base URL。
