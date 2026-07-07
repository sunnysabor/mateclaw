#!/usr/bin/env bash
# Export a tiny server-side deploy bundle for prebuilt-image deployment.
# The bundle contains only docker-compose.prebuilt.yml + scripts/deploy-prebuilt.sh.

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT="${1:-mateclaw-prebuilt-deploy.tar.gz}"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

mkdir -p "$TMP_DIR/mateclaw/scripts"
cp docker-compose.prebuilt.yml "$TMP_DIR/mateclaw/docker-compose.prebuilt.yml"
cp scripts/deploy-prebuilt.sh "$TMP_DIR/mateclaw/scripts/deploy-prebuilt.sh"
chmod +x "$TMP_DIR/mateclaw/scripts/deploy-prebuilt.sh"

# Include env example for operators who want to inspect variables, but the deploy
# script can generate .env automatically on first run.
cp .env.example "$TMP_DIR/mateclaw/.env.example"

# A tiny readme survives even when the full source tree is not uploaded.
cat > "$TMP_DIR/mateclaw/README_PREBUILT_DEPLOY.md" <<'README'
# MateClaw 预构建镜像部署包

服务器首次启动：

```bash
docker login 你的镜像仓库
bash scripts/deploy-prebuilt.sh --image-prefix 你的镜像仓库/命名空间 --tag 镜像标签
```

访问：`http://服务器IP:18080`

默认账号：`admin / admin123`
README

mkdir -p "$(dirname "$OUT")"
tar -C "$TMP_DIR" -czf "$OUT" mateclaw

echo "Exported: $OUT"
echo "Upload it to the server, then run:"
echo "  tar -xzf $(basename "$OUT")"
echo "  cd mateclaw"
echo "  bash scripts/deploy-prebuilt.sh --image-prefix <registry/namespace> --tag <tag>"
