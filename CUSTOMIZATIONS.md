# 二开改动记录

本文件记录本仓库相对官方 MateClaw 的长期定制。同步官方更新时，Agent / 开发者应优先保留这些明确登记的定制，但不能因此覆盖官方新增的核心功能。

## 维护原则

- 只记录需要长期保留、且可能影响 upstream 同步的改动。
- 每条记录写清目的、文件、风险和验证方式。
- 若某项定制已被官方吸收或废弃，应移动到“已废弃/已合并”区域。

## 当前长期定制

### 1. 桌面端 HHAIOS 品牌配置

- 目的：将桌面端品牌显示为 HHAIOS。
- 主要文件：
  - `mateclaw-desktop/branding.config.json`
  - `mateclaw-desktop/electron-builder.cjs`
  - `mateclaw-desktop/src/App.vue`
- 同步官方时处理建议：
  - 优先保留官方对桌面端启动、更新、打包逻辑的功能性修复。
  - 品牌名、appId、安装包命名等定制尽量通过配置层重新补回。
- 验证：
  - `cd mateclaw-desktop && pnpm run build`
  - 需要时执行 `BUILD_MODE=remote DESKTOP_BUILD_ARCH=arm64 pnpm exec electron-builder --mac --dir`

### 2. 远程服务端 / 轻客户端部署路径

- 目的：支持将服务端镜像部署到阿里云 ECS，桌面轻客户端连接远程服务。
- 主要文件：
  - `scripts/build-push-images.sh`
  - `scripts/deploy-prebuilt.sh`
  - `docker-compose.prebuilt.yml`
  - `mateclaw-desktop/electron/main/index.ts`
  - `mateclaw-desktop/electron/main/config.ts`
- 同步官方时处理建议：
  - 官方如果调整服务端端口、健康检查、静态资源路径或接口路径，应优先跟随官方。
  - 保留远程轻客户端必须连接远程地址、个人完整版可本地运行的产品分层。
- 验证：
  - `bash scripts/deploy-prebuilt.sh --help`
  - 远程服务可用时验证：`curl -I http://<server>:18080/`
  - 桌面端验证：远程轻客户端填写完整 `http://host:port` 地址后可进入系统。

## 已废弃 / 已被官方吸收

暂无。
