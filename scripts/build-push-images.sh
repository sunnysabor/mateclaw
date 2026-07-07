#!/usr/bin/env bash
#
# Build MateClaw runtime images on a build machine and push them to a registry.
# The target server can then run scripts/deploy-prebuilt.sh and skip source builds.
#
# Example:
#   docker login registry.cn-hangzhou.aliyuncs.com
#   bash scripts/build-push-images.sh registry.cn-hangzhou.aliyuncs.com/my-namespace --tag selftest

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

IMAGE_PREFIX="${MATECLAW_IMAGE_PREFIX:-}"
TAG="${MATECLAW_IMAGE_TAG:-latest}"
PLATFORM="${MATECLAW_IMAGE_PLATFORM:-linux/amd64}"
SEARXNG_BASE_IMAGE="${SEARXNG_IMAGE:-ghcr.io/searxng/searxng:latest}"
MAVEN_FLAGS="${MAVEN_FLAGS:-}"
PUSH=1

usage() {
  cat <<'USAGE'
MateClaw build & push images

Usage:
  bash scripts/build-push-images.sh IMAGE_PREFIX [options]

Arguments:
  IMAGE_PREFIX       Registry/repository prefix, e.g.
                     registry.cn-hangzhou.aliyuncs.com/my-namespace
                     Images pushed:
                       IMAGE_PREFIX/mateclaw-server:TAG
                       IMAGE_PREFIX/mateclaw-searxng:TAG

Options:
  --tag TAG          Image tag. Default: latest
  --platform VALUE   Build platform. Default: linux/amd64
  --no-push          Build locally with docker buildx --load instead of --push
  -h, --help         Show this help

Environment overrides:
  MATECLAW_IMAGE_PREFIX   Same as IMAGE_PREFIX
  MATECLAW_IMAGE_TAG      Same as --tag
  MATECLAW_IMAGE_PLATFORM Same as --platform
  MAVEN_FLAGS             Optional Maven flags, e.g. -Paliyun-first
  SEARXNG_IMAGE           Base SearXNG image. Default: ghcr.io/searxng/searxng:latest

Before running, login to your registry:
  docker login registry.cn-hangzhou.aliyuncs.com
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ $# -ge 2 ]] || { echo "Missing value for --tag" >&2; exit 2; }
      TAG="$2"
      shift 2
      ;;
    --platform)
      [[ $# -ge 2 ]] || { echo "Missing value for --platform" >&2; exit 2; }
      PLATFORM="$2"
      shift 2
      ;;
    --no-push)
      PUSH=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ -z "$IMAGE_PREFIX" ]]; then
        IMAGE_PREFIX="$1"
        shift
      else
        echo "Unknown option or duplicate IMAGE_PREFIX: $1" >&2
        usage
        exit 2
      fi
      ;;
  esac
done

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

sanitize_prefix() {
  local prefix="$1"
  prefix="${prefix%/}"
  if [[ -z "$prefix" ]]; then
    echo "IMAGE_PREFIX is required." >&2
    usage
    exit 2
  fi
  echo "$prefix"
}

main() {
  need_cmd docker
  if ! docker buildx version >/dev/null 2>&1; then
    echo "Docker buildx is required. Please install/enable Docker Buildx." >&2
    exit 1
  fi

  IMAGE_PREFIX="$(sanitize_prefix "$IMAGE_PREFIX")"
  local server_image="${IMAGE_PREFIX}/mateclaw-server:${TAG}"
  local searxng_image="${IMAGE_PREFIX}/mateclaw-searxng:${TAG}"
  local output_flag="--push"
  if [[ "$PUSH" -ne 1 ]]; then
    output_flag="--load"
  fi

  echo "Building images for platform: ${PLATFORM}"
  echo "SearXNG image: ${searxng_image}"
  echo "Server image:  ${server_image}"
  echo

  docker buildx build \
    --platform "$PLATFORM" \
    --build-arg "SEARXNG_IMAGE=${SEARXNG_BASE_IMAGE}" \
    -t "$searxng_image" \
    $output_flag \
    docker/searxng

  docker buildx build \
    --platform "$PLATFORM" \
    --build-arg "MAVEN_FLAGS=${MAVEN_FLAGS}" \
    -t "$server_image" \
    -f mateclaw-server/Dockerfile \
    $output_flag \
    .

  echo
  if [[ "$PUSH" -eq 1 ]]; then
    echo "Images pushed successfully:"
  else
    echo "Images built locally:"
  fi
  echo "  ${server_image}"
  echo "  ${searxng_image}"
  echo
  echo "Deploy on server with:"
  echo "  bash scripts/deploy-prebuilt.sh --image-prefix ${IMAGE_PREFIX} --tag ${TAG}"
}

main "$@"
