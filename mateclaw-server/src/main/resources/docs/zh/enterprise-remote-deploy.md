# 企业集中部署 + Desktop 远程客户端

这套方案适用于公司统一部署一台 MateClaw Server，员工本机只安装 Desktop 客户端并连接远程地址。

核心目标：

- 公司侧统一保存账号、权限、模型供应商、审计日志和业务数据。
- 员工侧不用安装 Java / Node / Maven，也不用各自维护一套服务端。
- 需要操作员工本机文件 / Shell 时，通过 Desktop 本地工具隧道完成，并在员工电脑上弹窗确认。

---

## 推荐拓扑

```text
员工电脑 Desktop Remote Client
        │ HTTPS / WSS
        ▼
https://mateclaw.company.com
        │ Nginx / Caddy / Ingress
        ▼
Docker Host
  ├─ mateclaw-server  :18088
  ├─ mysql            :3306
  └─ searxng          :8080
```

对员工只暴露：

- `443`：HTTPS / WSS
- 可选 `80`：跳转 HTTPS

不要对公网暴露：

- MySQL `3306`
- SearXNG `8088`
- OpenAI OAuth 本地回调端口 `1455`（远程部署默认走 device code / 粘贴授权流程）

---

## 1. 服务器准备

建议规格：

| 项 | 推荐 |
|---|---|
| 系统 | Linux |
| Docker Engine | 24+ |
| Docker Compose | v2 |
| 内存 | 8 GB+ |
| 磁盘 | 20 GB+，按 Wiki / 文件产物规模扩容 |
| 域名 | `mateclaw.company.com` |
| TLS | Let's Encrypt 或公司证书 |

如果 Agent 需要浏览器工具，`docker-compose.yml` 已给 `mateclaw-server` 设置 `shm_size: 2gb`，不要改小。

---

## 2. 服务端 Docker 部署

如果是简易自测，可以不用手工编辑配置，直接：

```bash
bash scripts/deploy-selftest.sh
```

脚本会自动生成 `.env`、随机密码、JWT secret，并启动 Docker Compose。自测默认访问地址是：

```text
http://服务器IP:18080
```

如果要指定域名或 IP：

```bash
bash scripts/deploy-selftest.sh --public-url http://你的服务器IP:18080
```

正式部署再按下面方式手工确认域名、CORS、证书和密码。

```bash
git clone <你的仓库地址> mateclaw
cd mateclaw

cp .env.example .env
vim .env
```

生产环境至少填写：

```properties
DB_NAME=mateclaw
DB_USERNAME=mateclaw
DB_PASSWORD=<强密码，建议 16 位以上>
DB_ROOT_PASSWORD=<另一个强密码>

JWT_SECRET=<openssl rand -base64 48>
MATECLAW_CORS_ALLOWED_ORIGINS=https://mateclaw.company.com
MATECLAW_PUBLIC_BASE_URL=https://mateclaw.company.com

SEARXNG_SECRET=<openssl rand -hex 32>
```

如果在中国大陆服务器上构建，可以启用 Maven 国内加速：

```properties
MAVEN_FLAGS=-Paliyun-first
```

启动：

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f mateclaw-server
```

本机验证：

```bash
curl -s http://127.0.0.1:18080/api/v1/system/health
curl -s http://127.0.0.1:18080/api/v1/system/browser-health
curl -s 'http://127.0.0.1:8088/search?q=hello&format=json' | head -5
```

---

## 3. 反向代理

Desktop 远程客户端需要普通 HTTP API，也需要 WebSocket：

- `/api/v1/desktop/ws`：Desktop 本地工具隧道
- `/api/v1/talk/ws`：语音 / Talk Mode
- SSE 聊天流也需要较长超时

Nginx 示例：

```nginx
server {
    listen 80;
    server_name mateclaw.company.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name mateclaw.company.com;

    ssl_certificate     /etc/letsencrypt/live/mateclaw.company.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/mateclaw.company.com/privkey.pem;

    client_max_body_size 200m;

    location / {
        proxy_pass http://127.0.0.1:18080;

        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

外部验证：

```bash
curl -I https://mateclaw.company.com
```

---

## 4. 首次初始化

浏览器打开：

```text
https://mateclaw.company.com
```

默认账号：

```text
admin / admin123
```

上线后立即做：

1. 修改 `admin` 密码。
2. 在「设置 → 模型」添加公司统一模型供应商。
3. 创建员工账号、工作空间和权限。
4. 配置 Tool Guard / File Guard / 审批策略。
5. 如需单点登录，再配置 SSO。

LLM API Key 不写进 `.env`，在管理界面「设置 → 模型」里配置，保存到数据库并支持热更新。

---

## 5. Desktop 客户端分发

### 推荐：Remote/Lite 安装包

Remote/Lite Desktop 不带内嵌 JRE 和 Server JAR，只能连接集中部署的远程 Server，适合公司统一分发。

构建：

```bash
cd mateclaw-desktop
npm install

# macOS remote/lite
npm run package:mac:remote

# Windows remote/lite
npm run package:win:remote
```

或构建全平台 remote/lite：

```bash
npm run build
npm run package:all:remote
```

产物在：

```text
mateclaw-desktop/release/
```

员工首次打开后填写：

```text
https://mateclaw.company.com
```

然后用公司分配的账号登录。

### 备选：完整 Desktop 安装包

完整 Desktop 带本地内嵌 JRE + Server JAR。它也能连接远程 Server，但会保留“本地模式”选项，安装包更大。适合需要同一个安装包同时覆盖个人离线和公司远程两种场景。

---

## 6. 员工本地工具隧道

远程 Desktop 登录后，会用当前用户 JWT 建立：

```text
Desktop ── WSS /api/v1/desktop/ws ── Server
```

它允许远程 Agent 调用员工本机能力：

- `local_read_file`
- `local_list_dir`
- `local_write_file`
- `local_edit_file`
- `local_shell`

安全边界：

- Desktop 端维护本地目录白名单。
- 写文件、编辑文件、执行 Shell 会在员工电脑上弹原生确认框。
- 服务端按登录用户查找该用户当前在线 Desktop 隧道，不能串到别人的电脑。
- 没有 Desktop 在线时，本地工具会失败，Agent 应提示用户打开 Desktop 或改用服务端工具。

推荐公司策略：

1. 默认只允许明确白名单目录。
2. Shell 执行必须员工确认。
3. 高风险工具在服务端 Tool Guard 中设置为 `require_approval`。
4. 不要把员工 home 根目录、磁盘根目录加入白名单。

---

## 7. 内网访问与浏览器工具

默认浏览器工具会阻断私有 IP、回环地址和云元数据地址，防止 SSRF。

如果你的部署是纯内网场景，Agent 需要访问 `192.168.x.x`、`10.x.x.x` 或内部域名，可以在 `.env` 里开启：

```properties
PLAYWRIGHT_ALLOW_PRIVATE_NETWORK=true
```

只有在确实使用自签名 HTTPS 且能接受风险时，才考虑：

```properties
PLAYWRIGHT_IGNORE_HTTPS_ERRORS=true
```

公网部署保持这两个值为 `false` 更安全。

---

## 8. 备份与升级

至少备份：

- `.env`
- `mysql_data` 数据卷
- `server_data` 数据卷

MySQL 逻辑备份示例：

```bash
docker exec mateclaw-mysql \
  mysqldump -u root -p mateclaw > backup-$(date +%F).sql
```

服务端升级：

```bash
git pull
docker compose build mateclaw-server
docker compose up -d mateclaw-server
docker compose logs -f mateclaw-server
```

MySQL 数据卷不会被这组命令删除。应用启动时会自动执行 Flyway 增量迁移。

Desktop 客户端升级：

- 如果使用 GitHub Releases / electron-updater，发布新的 remote/lite 安装包和更新元数据。
- 如果公司内部软件分发系统统一推送，直接分发 `release/` 目录里的安装包。

---

## 9. 验收清单

服务端：

- [ ] `docker compose ps` 三个服务正常。
- [ ] `https://mateclaw.company.com` 可打开。
- [ ] `admin` 默认密码已修改。
- [ ] `JWT_SECRET` 已设置。
- [ ] `MATECLAW_CORS_ALLOWED_ORIGINS` 指向正式域名。
- [ ] `MATECLAW_PUBLIC_BASE_URL` 指向正式域名。
- [ ] 模型供应商已在 UI 中配置并能返回 token 流。
- [ ] 反向代理支持 WebSocket。

员工端：

- [ ] Remote/Lite Desktop 可安装。
- [ ] 首次连接 `https://mateclaw.company.com` 成功。
- [ ] 员工账号可登录。
- [ ] Desktop 远程连接可记住服务器。
- [ ] 如需本地工具，员工已配置本地目录白名单。
- [ ] 写文件 / Shell 操作会弹本地确认。

运维：

- [ ] MySQL 和 `server_data` 有定时备份。
- [ ] 升级流程在预发环境演练过。
- [ ] 证书续期已自动化。
- [ ] 日志采集和磁盘告警已配置。

---

## 常见问题

### Desktop 连接远程地址失败

先在员工电脑浏览器打开同一个地址：

```text
https://mateclaw.company.com
```

如果浏览器也打不开，先查 DNS、证书、反向代理、防火墙。

如果浏览器能打开但 Desktop 失败，检查是否使用了自签名证书。Desktop 只会对用户显式信任的当前 host 放行，不会通配放行未知证书。

### 聊天能用，但本地工具不可用

检查：

1. 员工是否用 Desktop 登录，而不是普通浏览器。
2. 反向代理是否允许 WebSocket Upgrade。
3. Desktop 本地工具是否启用。
4. 本地目录是否在白名单内。

### SSE 流式响应中断

把反向代理超时调大：

```nginx
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
```

### 上传大文件失败

检查 Nginx：

```nginx
client_max_body_size 200m;
```

并确认服务端 `spring.servlet.multipart` 限制没有被覆盖成更小。

---

## 相关文档

- [Docker 部署](./docker-deploy)
- [桌面应用](./desktop)
- [配置参考](./config)
- [安全与审批](./security)
- [模型配置](./models)
