# AGENTS.md — MateClaw 二开协作规范

本仓库是基于官方 MateClaw 的二次开发仓库。后续所有 Agent / Codex / 自动化改动都必须遵守以下原则：

## 核心原则：上游优先 + 二开补丁层

- **官方 upstream 是功能稳定性的主来源。** 同步官方更新时，默认以 `upstream/dev` 为主。
- **本仓库的二开改动必须保持小、清晰、可回放、可回滚。** 不要把官方核心逻辑改散。
- **能用配置、环境变量、品牌配置、部署脚本参数解决的，不直接修改核心业务代码。**
- **任何二开行为都要降低对官方后续更新的冲突面积。**

## Git 远程约定

- `upstream`：官方仓库，只读同步来源，例如 `mateaix/mateclaw`。
- `origin`：当前二开 fork，例如 `sunnysabor/mateclaw`。
- `dev`：当前二开集成分支，可触发 CI/安装包构建。
- `feature/*`：二开功能分支。
- `sync/upstream-YYYYMMDD`：同步官方更新的临时集成分支。

## 默认开发流程

1. 不要直接在 `dev` 上做大改动。
2. 新功能/修复从 `dev` 拉分支：
   ```bash
   git checkout dev
   git checkout -b feature/<short-name>
   ```
3. 改动保持最小化，优先配置化、脚本化、隔离化。
4. 提交信息建议使用：
   - `custom: ...` 二开定制
   - `fix: ...` 修复
   - `docs: ...` 文档
   - `sync: ...` 官方同步
   - `chore: ...` 构建/维护
5. 合回 `dev` 前必须做针对性验证。

## 同步官方更新流程

详见：`docs/upstream-first-workflow.md`。

简要流程：

```bash
git fetch upstream origin --prune
git checkout dev
git pull origin dev
git checkout -b sync/upstream-$(date +%Y%m%d)
git merge --no-ff upstream/dev
```

如果发生冲突：

- 默认优先官方实现；
- 只有确认为本仓库必须保留的二开逻辑，才重新补回；
- 不要无意识地用二开改动覆盖官方更新。

## 二开改动登记要求

凡是会长期保留、且可能影响官方同步的二开改动，必须记录到：

- `CUSTOMIZATIONS.md`

记录内容至少包括：

- 改动目的
- 涉及文件
- 与官方代码的关系
- 同步官方时的处理建议
- 验证方式

## 验证要求

修改代码后，按影响范围运行最小必要验证：

- 后端：`mvn test` 或模块级 Maven 测试。
- 前端：对应包的 `pnpm test` / `pnpm run build`。
- 桌面端：`cd mateclaw-desktop && pnpm run build`，必要时执行 `electron-builder` 打包验证。
- Docker/部署：至少检查 compose/script 参数与镜像名是否一致。

不能运行验证时，最终说明必须写清原因和风险。

## 桌面端二开注意事项

- 远程轻客户端依赖远程服务地址；个人完整版应包含本地 JRE 与 `app.jar`。
- 品牌、安装包命名、发布仓库优先通过 `mateclaw-desktop/branding.config.json`、`electron-builder.cjs`、环境变量处理。
- macOS 面向普通用户分发时，应考虑签名和公证；不要把未签名包误判为业务启动失败。

## Agent 行为约束

- 先读本文件和相关模块文档，再修改。
- 不确定是否会扩大冲突面时，先选择更小的改动方式。
- 不新增依赖，除非用户明确要求或已有方案无法满足。
- 不提交密钥、密码、token、私有服务器凭据。
- 提交前检查 `git diff`，避免把本地构建产物、日志、临时文件提交进去。
