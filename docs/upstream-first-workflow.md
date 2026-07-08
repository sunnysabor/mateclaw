# 上游优先同步工作流

本项目是基于官方 MateClaw 的二次开发仓库。目标是持续吸收官方更新，同时让本仓库的定制改动保持稳定、可控、可回放。

## 目标

- 官方更新优先，降低偏离官方主线的风险。
- 二开改动作为补丁层存在，尽量小而清晰。
- 每次同步官方代码都可审查、可验证、可回滚。
- 后续 Agent 写代码时能明确知道哪些改动是本仓库定制，哪些应跟随官方。

## 远程和分支约定

| 名称 | 用途 |
| --- | --- |
| `upstream` | 官方仓库，只读同步来源 |
| `origin` | 当前二开 fork |
| `dev` | 当前二开集成分支 |
| `feature/*` | 二开功能/修复分支 |
| `sync/upstream-YYYYMMDD` | 同步官方更新的临时分支 |
| `release/*` | 可选，稳定发布分支 |

检查远程：

```bash
git remote -v
```

## 日常二开开发流程

```bash
git fetch origin --prune
git checkout dev
git pull origin dev
git checkout -b feature/<short-name>
```

开发时遵守：

1. 优先配置化，不改官方核心逻辑。
2. 一个分支只解决一个明确问题。
3. 提交要小，便于以后同步官方时回放/丢弃。
4. 长期保留的定制必须登记到 `CUSTOMIZATIONS.md`。

推荐提交前检查：

```bash
git status --short
git diff --stat
git diff
```

## 同步官方更新流程

### 1. 创建同步分支

```bash
git fetch upstream origin --prune
git checkout dev
git pull origin dev
git checkout -b sync/upstream-$(date +%Y%m%d)
```

### 2. 合并官方 dev

推荐普通 merge，便于显式处理冲突：

```bash
git merge --no-ff upstream/dev
```

如果用户明确要求“冲突默认全部官方优先”，可以使用：

```bash
git merge --no-ff -X theirs upstream/dev
```

注意：`-X theirs` 只影响冲突块，不代表所有文件都完全采用官方版本；仍然必须审查 diff。

### 3. 冲突处理原则

冲突时默认判断顺序：

1. 官方是否修改了核心功能、接口、数据结构或构建流程？如果是，优先官方。
2. 本仓库改动是否只是品牌、部署、打包、默认配置？如果是，尽量重新以最小补丁补回。
3. 若二开改动会覆盖官方新增功能，默认不要覆盖，先保留官方。
4. 对必须保留的定制，记录到 `CUSTOMIZATIONS.md`。

常用命令：

```bash
# 查看冲突文件
git status --short

# 某个冲突文件采用官方版本
git checkout --theirs path/to/file

# 某个冲突文件采用当前二开版本
git checkout --ours path/to/file

# 手工处理后标记解决
git add path/to/file
```

### 4. 审查同步结果

```bash
git diff --stat dev...HEAD
git log --oneline --decorate --graph --max-count=30
```

重点看：

- 是否误删了二开必需配置。
- 是否把构建产物、日志、临时文件加入提交。
- 桌面端安装包命名、品牌、远程/本地模式是否仍符合预期。
- Docker compose、部署脚本、镜像前缀是否仍可用。

### 5. 验证

按影响范围选择最小验证集。

后端示例：

```bash
mvn test
```

前端示例：

```bash
cd mateclaw-ui
pnpm install
pnpm test
pnpm run build
```

桌面端示例：

```bash
cd mateclaw-desktop
pnpm install
pnpm run build
```

远程轻客户端打包示例：

```bash
cd mateclaw-desktop
BUILD_MODE=remote DESKTOP_BUILD_ARCH=arm64 pnpm exec electron-builder --mac --dir
```

部署脚本检查示例：

```bash
bash scripts/deploy-prebuilt.sh --help
```

### 6. 合回 dev 并推送

验证通过后：

```bash
git checkout dev
git merge --no-ff sync/upstream-YYYYMMDD
git push origin dev
```

推送 `dev` 通常会触发 CI/安装包打包。

## 回滚策略

如果同步后发现问题：

```bash
git checkout dev
git log --oneline
# 找到同步 merge commit
git revert -m 1 <merge-commit-sha>
git push origin dev
```

不要直接强推覆盖公共分支，除非用户明确要求并确认影响。

## Agent 执行要求

Agent 在本仓库执行同步/开发时：

1. 必须先读取根目录 `AGENTS.md`。
2. 涉及长期二开定制时，更新 `CUSTOMIZATIONS.md`。
3. 涉及官方同步时，使用 `sync/upstream-YYYYMMDD` 分支。
4. 冲突默认以官方为主，但要保留明确登记的二开补丁。
5. 最终报告必须包含：
   - 当前分支与提交
   - 同步来源
   - 冲突处理摘要
   - 验证命令和结果
   - 剩余风险
