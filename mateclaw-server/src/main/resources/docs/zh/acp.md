---
title: ACP 接入 —— 把外部编码 Agent 接进 HHAIOS
description: HHAIOS 作为 ACP 宿主，通过 stdio 把 prompt 转交给 Hermes、Codex、OpenClaw 等 Agent Client Protocol 端点。内置端点、可视化环境变量编辑、自动桥接技能卡、信任模型、错误翻译。
head:
  - - meta
    - name: keywords
      content: ACP,Agent Client Protocol,Claude Code,Codex,OpenCode,Qwen Code,外部 Agent,stdio JSON-RPC,编码 Agent 接入
---

# ACP —— Agent Client Protocol

**ACP 是 HHAIOS 把 prompt 交给别人写的 Agent 的方式。**

Agent Client Protocol 是一个开放规范，定义 Agent 客户端通过 JSON-RPC 调用 Agent 服务端的协议。HHAIOS 扮演 **宿主**：拉起一个外部 CLI（Hermes、Codex、OpenClaw …），通过 stdio 完成 `initialize` → `session/new` → `session/prompt` 三步握手，把流式响应回填到对话里。

如果说 MCP 是 "插一个工具"，ACP 就是 **"插一整个 Agent"**。HHAIOS 现在支持两种 ACP 使用方式：把 Hermes / Codex / OpenClaw 创建成一等数字员工直接聊天；或者把启用的端点桥接成 `acp_<slug>_prompt` 虚拟技能，让现有 HHAIOS 员工在轮次里委派给外部 Agent。

---

## ACP vs MCP 一眼区分

| | **MCP** | **ACP** |
|---|---|---|
| 接什么 | 工具服务器 | Agent |
| 粒度 | 按工具（`tools/list`） | 按 Agent 会话或按 prompt 委派 |
| HHAIOS 的传输 | stdio / streamable_http / sse | stdio |
| 会话模型 | 长连接、多次调用 | 一等员工：按会话保活；虚拟技能：一次性调用 |
| 典型用法 | 文件系统、搜索、自定义数据 API | 把编码任务交给 Hermes / Codex / OpenClaw |
| 在 HHAIOS 的呈现 | 工具目录 | 员工运行时 + 技能目录（自动桥接）+ 工具包装 |

同一个 HHAIOS 内置员工可以同时用 MCP 和 ACP 虚拟技能；ACP 一等员工第一版只运行外部 Agent，不绑定 HHAIOS 技能、工具或知识库。

---

## 内置端点

随 HHAIOS 出厂的 Flyway 迁移会预置三个一等端点，**默认全部禁用**——你装好对应 CLI 之后再打开。

| 标识 | 显示名 | Command | 备注 |
|---|---|---|---|
| `hermes` | Hermes Agent | `hermes acp --accept-hooks` | Hermes 原生 ACP server |
| `codex` | OpenAI Codex CLI | `npx -y @agentclientprotocol/codex-acp` | Codex 通过 ACP adapter 接入 |
| `openclaw` | OpenClaw | `openclaw acp` | OpenClaw Gateway-backed ACP bridge |

托管内置行写保护——不能改 slug、不能删除；Hermes / Codex / OpenClaw 允许把 `command` 改成绝对路径，也可以按需调整 `args_json` / `env_json` / `description` / `trusted` / `enabled`。一等 ACP 员工第一版只支持这三个托管端点。要把别的 Agent 作为虚拟技能调用，仍然可以新建自定义端点。

---

## 在控制台配置

`设置 → ACP 端点` 是完整的 CRUD 入口。

### 新建 / 编辑端点

- **Slug** —— 小写标识符（如 `hermes`），创建后不可修改。技能通过 slug 引用端点。
- **显示名** —— 技能页展示用的人类标签。
- **描述** —— 运维备注。
- **Command** —— 可执行文件（`hermes`、`npx`、`openclaw` …）。托管内置端点允许改成绝对路径，适配桌面进程 `PATH` 不完整的情况。
- **Args（JSON 数组）** —— CLI 参数，例如 `["acp","--accept-hooks"]` 或 `["-y","@agentclientprotocol/codex-acp"]`。
- **Env（JSON 对象）** —— 注入子进程的额外环境变量。可视化编辑器会把 key 命中 `*API_KEY*` / `*TOKEN*` / `*SECRET*` / `*PASS*` 的值自动打码。
- **Tool parse mode** —— `call_title` / `call_detail` / `update_detail`，决定上游工具调用事件渲染到流式抄本的方式。
- **Trusted** —— 打开时，HHAIOS 会自动同意上游 Agent 发来的 `session/request_permission`；关闭时，所有权限请求一律拒绝（适合非交互场景）。
- **Enabled** —— 启停开关。禁用的端点不会进入技能目录。

### 测试连接

点击 **Test** 会拉起进程、跑一遍 `initialize` + `session/new`，再关掉。结果面板显示协议版本、Agent 能力、耗时，失败时附带翻译过的错误提示（见 [信任与错误翻译](#trust-error-translation)）。状态会写回行上：`last_status` / `last_tested_at` / `last_error`。

### 启用 / 停用 / 删除

- **Toggle** —— 把端点从目录里摘掉但不删除。
- **Delete** —— 仅自定义端点可删，内置端点拒绝删除。

任何变更都会发出 `AcpEndpointChangedEvent`，技能目录立刻重新同步——不需要重启服务。

---

## REST API

基础路径：`/api/v1/acp/endpoints`，需要 JWT。

| Method | Path | 作用 |
|---|---|---|
| `GET`    | `/`              | 列出全部端点 |
| `GET`    | `/{id}`          | 取单条 |
| `POST`   | `/`              | 新建自定义端点 |
| `PUT`    | `/{id}`          | 局部更新（托管内置端点允许修正 `command` 路径） |
| `DELETE` | `/{id}`          | 删除自定义端点（内置拒绝） |
| `PUT`    | `/{id}/toggle?enabled=true\|false` | 启用 / 停用 |
| `POST`   | `/{id}/test`     | 跑连接测试 |

### 新建自定义端点

```bash
curl -X POST http://localhost:18088/api/v1/acp/endpoints \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "name": "my-coder",
    "displayName": "我的自研 Agent",
    "description": "内部编码 Agent",
    "command": "npx",
    "argsJson": "[\"-y\",\"@my-org/my-acp-agent\"]",
    "envJson": "{\"MY_API_KEY\":\"sk-...\"}",
    "toolParseMode": "call_detail",
    "trusted": true,
    "enabled": true
  }'
```

### 测试端点

```bash
curl -X POST http://localhost:18088/api/v1/acp/endpoints/9100005/test \
  -H "Authorization: Bearer <token>"
```

返回示例：

```json
{
  "name": "hermes",
  "command": "hermes",
  "args": ["acp", "--accept-hooks"],
  "agentCapabilities": { "loadSession": false, "promptCapabilities": { "image": true } },
  "status": "OK",
  "elapsedMs": 1842
}
```

失败时 `status` 为 `ERROR`，`error` 字段是翻译后的提示。

---

## 端点是怎么被 Agent 用上的

三条路径：

### 1. 一等 ACP 员工（直接聊天）

适合你希望“这个员工本身就是 Hermes / Codex / OpenClaw”的场景。

操作路径：

1. 到 `设置 → ACP 端点`，确认对应端点已启用，并且 **Test** 通过。
2. 到 `员工 → 新建员工` 或编辑已有员工。
3. 在类型下拉框选择 `外部 ACP Agent`。
4. 在外部 Agent 下拉框选择 `Hermes`、`Codex` 或 `OpenClaw`。
5. 保存后，到聊天页选择这个员工，直接发消息。

一等 ACP 员工的运行方式：

- HHAIOS 按 `agentId + conversationId` 保持一个 ACP 进程和 session。
- 同一会话连续对话时，Hermes / Codex / OpenClaw 可以使用自己的原生上下文。
- 空闲 30 分钟后 HHAIOS 会关闭进程；下一轮自动创建新 session。
- 重建 session 时，HHAIOS 会从已持久化的最近 20 条历史消息注入恢复上下文，并在聊天流里给出“已根据历史记录恢复”的 warning。
- 历史消息仍然保存在 HHAIOS 会话里；但外部 ACP 进程里的内存无法 100% 原样恢复。
- 第一版不允许给 ACP 员工绑定 HHAIOS 技能、工具或知识库，保存时会自动关闭这些能力，绑定接口也会拒绝写入。
- ACP 员工不使用 HHAIOS 模型配置；聊天页会隐藏模型选择器，后端也不会走本地 Agent 图。

### 2. 自动桥接的虚拟技能（零配置）

每个启用的端点都会被注册一张虚拟技能卡，并在工具注册表里多出一个名为 `acp_<slug>_prompt` 的包装工具。它接收一个 `prompt` 字符串参数，返回上游 Agent 累积的文本回答。任何数字员工都可以像调内置工具一样调用它，不用写技能清单。

```
设置 → ACP 端点（打开开关）
   ↓
AcpEndpointChangedEvent
   ↓
技能目录新增 "Hermes Agent" 卡片
工具注册表新增 "acp_hermes_prompt"
   ↓
Agent 调用工具 → AcpDelegationService.prompt()
   ↓
拉起 → initialize → session/new → session/prompt
   ↓
累积 agent-message-chunk 通知
   ↓
把文本回填到 Agent 的轮次
```

虚拟技能是一次性委派：每次工具调用都会拉起新进程、创建新 session、发送 prompt，然后关闭进程。它适合“让当前 HHAIOS 员工把某个子任务交给外部编码 Agent”，不适合把外部 Agent 当成整个员工持续聊天。

### 3. 手写技能（完全可控）

技能清单可以声明 `type: acp` 并绑定到某个端点。技能会得到自己的包装工具（`acp_<endpoint>_<skill>_prompt`），可以在每次 prompt 前注入 `systemPrefix`，也可以按会话覆盖 `cwd`。

```yaml
# SKILL.md frontmatter
type: acp
acp:
  endpoint: hermes
  systemPrefix: |
    你正在 HHAIOS 仓库里工作。报完成前一定要先跑 `mvn test`。
  cwd: /workspaces/mateclaw
```

`codex-helper` 这类出厂技能模板就是这么做的。

---

## 信任与错误翻译 {#trust-error-translation}

### 信任开关

ACP 服务端可以在做敏感动作前（写文件、跑 shell 命令等）发 `session/request_permission` 请宿主放行。HHAIOS **不会** 在流式响应中途打断用户去问，而是按端点的 `trusted` 标志决定：

- `trusted: true` —— 自动选择 Agent 给的第一个选项放行。适合你自己装好的可信 CLI。
- `trusted: false` —— 所有权限请求一律拒绝。适合沙盒或不可信端点；上游 Agent 会优雅退避。

### 错误翻译

编码 Agent 的报错出了名地难懂。`AcpRuntimeSupport.translateAuthError()` 识别常见 401 / 403 / "Request not allowed" 模式，把它们改写成可执行建议：

- 缺密钥 → 提示 "请设置 `ANTHROPIC_API_KEY`" / `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` / `GOOGLE_API_KEY`，按端点对应。
- Claude Code OAuth 钥匙串劫持 → 建议跑 `claude logout`，把 `~/.claude/` 里盖住你环境变量的旧 OAuth token 清掉。

提示会在测试面板里弹出，也会带进 Agent 收到的流式错误信息里。

### 超时与限额

- `initialize` 握手：15 秒
- `session/new`：10 秒
- 一等 ACP 员工单轮 `session/prompt`：10 分钟
- 一等 ACP 员工空闲 session：30 分钟后回收
- 虚拟技能单次 `session/prompt`：5 分钟
- stdio 缓冲上限：单次 50 MiB（行级 `stdio_buffer_limit_bytes` 可改）

---

## 数据库 —— `mate_acp_endpoint`

| 列 | 类型 | 默认 | 用途 |
|---|---|---|---|
| `id` | BIGINT | — | 主键，托管内置端点占用 `9100001`、`9100005`、`9100006` |
| `name` | VARCHAR(64) | — | 唯一 slug，技能引用此字段 |
| `display_name` | VARCHAR(128) | NULL | 显示名 |
| `description` | TEXT | NULL | 运维备注 |
| `command` | VARCHAR(256) | — | 进程命令 |
| `args_json` | TEXT | NULL | CLI 参数（JSON 数组） |
| `env_json` | TEXT | NULL | 环境变量覆盖（JSON 对象） |
| `tool_parse_mode` | VARCHAR(32) | `call_title` | `call_title` / `call_detail` / `update_detail` |
| `builtin` | BOOLEAN | FALSE | 内置行写保护 |
| `trusted` | BOOLEAN | TRUE | 自动放行权限请求 |
| `enabled` | BOOLEAN | FALSE | 默认关闭，按需打开 |
| `stdio_buffer_limit_bytes` | BIGINT | 52428800 | 单次 stdio 累积 50 MiB 上限 |
| `last_status` | VARCHAR(32) | NULL | `OK` / `ERROR` |
| `last_tested_at` | DATETIME | NULL | 上次测试时间 |
| `last_error` | TEXT | NULL | 上次测试错误 |
| `workspace_id` | BIGINT | 1 | 绑定的工作空间 |
| `create_time` / `update_time` | DATETIME | — | 时间戳 |
| `deleted` | INT | 0 | 逻辑删除 |

DDL 在 `db/migration/{h2,mysql}/V68__add_acp_endpoints.sql`。

## 数据库 —— `mate_agent.acp_endpoint_name`

一等 ACP 员工使用 `mate_agent.agent_type='acp'` 标识运行时，并把外部端点写入 `mate_agent.acp_endpoint_name`。该列由 `db/migration/{h2,mysql,kingbase}/V167__agent_acp_runtime.sql` 添加。

保存 ACP 员工时，服务端会做这些归一化：

- `agent_type` 固定为 `acp`。
- `acp_endpoint_name` 只允许 `hermes`、`codex`、`openclaw`。
- `skills_disabled=true`、`tools_disabled=true`、`wiki_disabled=true`。
- 非 ACP 员工会清空 `acp_endpoint_name`。

---

## 排查

### "Command not found"

`command` 必须在跑 HHAIOS 的用户的 `PATH` 里。`which hermes`、`which npx`、`which openclaw` 确认一下。Docker 里要把 CLI 装进镜像。桌面服务进程经常拿不到 shell 的 `PATH`，这时可以在端点里把 `command` 写成绝对路径。

### Claude Code 报 "Request not allowed" / 403

多半是你 `~/.claude/` 里有个 OAuth token 缓存，盖住了你在环境变量编辑器里设的 `ANTHROPIC_API_KEY`。跑一次 `claude logout`，再点 **Test** 试试。测试面板检测到这种情况会主动提示。

### `session/new` 卡住

通常是上游 CLI 在首次启动时下载依赖（`npx -y` 会这样）。要么先在 HHAIOS 之外手动跑一次 CLI 把依赖预热好，要么直接重试——后续调用都很快。

### "Subprocess output exceeded buffer"

Agent 在一次调用里输出超过了 50 MiB 的 stdio。把端点行的 `stdioBufferLimitBytes` 调大，或者把 prompt 拆成多轮。

### 工具没出现在技能页

- 确认 `enabled: true`。
- 确认测试通过（`last_status: OK`）。
- 看一眼 Agent 的工具绑定——自动桥接的工具默认对所有数字员工可用，除非被明确排除。

---

## 下一步

- [技能系统](./skills) —— 包括手写的 `type: acp` 技能
- [工具系统](./tools) —— 包装工具是怎么进注册表的
- [MCP 协议](./mcp) —— 服务工具用的姊妹协议
- [安全与审批](./security) —— 信任开关怎么和工具守卫配合
