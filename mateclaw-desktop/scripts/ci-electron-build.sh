#!/usr/bin/env bash
# Build one Electron desktop target for CI and treat the job as successful when
# the expected installer artifact exists. electron-builder can occasionally
# return a non-zero status after producing the installer (for example during DMG
# detach or metadata cleanup on hosted runners); in that case we still upload the
# usable installer plus the captured log.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <mac|win|linux> <x64|arm64>" >&2
  exit 2
fi

platform="$1"
arch="$2"
build_mode="${BUILD_MODE:-remote}"

case "$build_mode" in
  remote) variant_label="企业远程轻客户端" ;;
  local)  variant_label="个人版客户端（完整本地客户端）" ;;
  *) echo "Unsupported BUILD_MODE: $build_mode" >&2; exit 2 ;;
esac

case "$platform" in
  mac|win|linux) ;;
  *) echo "Unsupported platform: $platform" >&2; exit 2 ;;
esac

case "$arch" in
  x64|arm64) ;;
  *) echo "Unsupported architecture: $arch" >&2; exit 2 ;;
esac

mkdir -p ci-logs release
log="ci-logs/electron-builder-${build_mode}-${platform}-${arch}.log"

set +e
BUILD_MODE="$build_mode" \
DESKTOP_BUILD_ARCH="$arch" \
pnpm exec electron-builder "--${platform}" 2>&1 | tee "$log"
build_status=${PIPESTATUS[0]}
set -e

echo "electron-builder exit code: ${build_status}" | tee -a "$log"
echo "release directory after build:" | tee -a "$log"
find release -maxdepth 1 -type f -print | sort | tee -a "$log" || true

shopt -s nullglob
case "$platform" in
  mac)
    installers=(release/*_"${variant_label}"_*_${arch}.dmg release/*_"${variant_label}"_*_${arch}.zip)
    required_label="macOS ${arch} ${variant_label} .dmg/.zip"
    ;;
  win)
    installers=(release/*_"${variant_label}"_*_${arch}_Setup.exe)
    required_label="Windows ${arch} ${variant_label} NSIS .exe"
    ;;
  linux)
    installers=(release/*_"${variant_label}"_*.AppImage release/*_"${variant_label}".AppImage)
    required_label="Linux ${variant_label} AppImage"
    ;;
esac

if [ "${#installers[@]}" -eq 0 ]; then
  echo "ERROR: Missing expected installer artifact: ${required_label}" | tee -a "$log" >&2
  if [ "$build_status" -ne 0 ]; then
    exit "$build_status"
  fi
  exit 1
fi

printf 'Found expected installer artifact(s):\n' | tee -a "$log"
printf '  %s\n' "${installers[@]}" | tee -a "$log"

if [ "$build_status" -ne 0 ]; then
  echo "WARNING: electron-builder exited with ${build_status}, but expected installer artifacts exist; continuing so artifacts can be downloaded." | tee -a "$log" >&2
fi
