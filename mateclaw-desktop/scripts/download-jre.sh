#!/usr/bin/env bash
#
# scripts/download-jre.sh — Download Eclipse Temurin JRE 21 for the current
# platform and extract into resources/jre/<platform>-<arch>/.
#
# Usage:
#   scripts/download-jre.sh            # auto-detect current arch
#   scripts/download-jre.sh arm64      # arm64 only
#   scripts/download-jre.sh x64        # x64 only
#   scripts/download-jre.sh all        # both arches when supported
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JRE_DIR="$PROJECT_ROOT/resources/jre"

# Temurin 21 (LTS) JRE downloads via Adoptium API.
ADOPTIUM_BASE="https://api.adoptium.net/v3/binary/latest/21/ga"

case "$(uname -s)" in
  Darwin*)
    API_OS="mac"
    FOLDER_OS="mac"
    ARCHIVE_EXT="tar.gz"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    API_OS="windows"
    FOLDER_OS="win32"
    ARCHIVE_EXT="zip"
    ;;
  Linux*)
    API_OS="linux"
    FOLDER_OS="linux"
    ARCHIVE_EXT="tar.gz"
    ;;
  *)
    echo "Unsupported OS: $(uname -s)" >&2
    exit 1
    ;;
esac

python_bin() {
  if command -v python3 >/dev/null 2>&1; then
    command -v python3
  elif command -v python >/dev/null 2>&1; then
    command -v python
  else
    return 1
  fi
}

move_dir_contents() {
  local from="$1"
  local to="$2"
  mkdir -p "$to"
  (shopt -s dotglob nullglob; mv "$from"/* "$to"/)
}

extract_archive() {
  local archive="$1"
  local destination="$2"

  mkdir -p "$destination"
  if [ "$ARCHIVE_EXT" = "zip" ]; then
    local py
    py="$(python_bin)" || {
      echo "ERROR: python3/python is required to extract Windows JRE zip archives" >&2
      exit 1
    }
    "$py" - "$archive" "$destination" <<'PY'
import sys
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as zf:
    zf.extractall(sys.argv[2])
PY
  else
    tar -xzf "$archive" -C "$destination"
  fi
}

find_extracted_root() {
  local extract_tmp="$1"
  find "$extract_tmp" -mindepth 1 -maxdepth 1 -type d | head -1
}

download_and_extract() {
  local api_arch="$1"
  local folder="$2"
  local url="$ADOPTIUM_BASE/$API_OS/$api_arch/jre/hotspot/normal/eclipse?project=jdk"
  local tmpfile="$JRE_DIR/jre-$FOLDER_OS-$api_arch.$ARCHIVE_EXT"
  local extract_tmp="$JRE_DIR/.tmp-$FOLDER_OS-$api_arch"
  local target_dir="$JRE_DIR/$folder"

  echo "==> Downloading Temurin 21 JRE for $API_OS $api_arch"
  mkdir -p "$JRE_DIR"
  curl -L --fail -o "$tmpfile" "$url"

  echo "==> Extracting to $target_dir"
  rm -rf "$target_dir" "$extract_tmp"
  mkdir -p "$target_dir" "$extract_tmp"

  extract_archive "$tmpfile" "$extract_tmp"

  local extracted_dir
  extracted_dir="$(find_extracted_root "$extract_tmp")"
  if [ -z "$extracted_dir" ]; then
    echo "ERROR: Could not find extracted JRE directory" >&2
    exit 1
  fi

  if [ "$API_OS" = "mac" ]; then
    # Temurin macOS archives extract to: jdk-21.x.x+jre/Contents/Home/...
    if [ -d "$extracted_dir/Contents" ]; then
      mv "$extracted_dir/Contents" "$target_dir/Contents"
    elif [ -d "$extract_tmp/Contents" ]; then
      mv "$extract_tmp/Contents" "$target_dir/Contents"
    else
      echo "ERROR: Could not find macOS JRE Contents directory" >&2
      exit 1
    fi
  else
    # Windows/Linux archives extract with bin/, lib/, release, ... at top level.
    move_dir_contents "$extracted_dir" "$target_dir"
  fi

  rm -rf "$extract_tmp" "$tmpfile"

  local java_bin
  if [ "$API_OS" = "mac" ]; then
    java_bin="$target_dir/Contents/Home/bin/java"
  elif [ "$API_OS" = "windows" ]; then
    java_bin="$target_dir/bin/java.exe"
  else
    java_bin="$target_dir/bin/java"
  fi

  if [ -f "$java_bin" ]; then
    echo "==> OK: $java_bin"
  else
    echo "ERROR: java binary not found at $java_bin" >&2
    exit 1
  fi
}

TARGET="${1:-auto}"
if [ "$TARGET" = "auto" ]; then
  case "$(uname -m)" in
    arm64|aarch64) TARGET="arm64" ;;
    x86_64|amd64)  TARGET="x64" ;;
    *) echo "Unsupported arch: $(uname -m)" >&2; exit 1 ;;
  esac
fi

case "$TARGET" in
  arm64)
    if [ "$API_OS" = "windows" ]; then
      echo "Windows arm64 JRE packaging is not configured for this project." >&2
      exit 1
    fi
    download_and_extract "aarch64" "$FOLDER_OS-arm64"
    ;;
  x64)
    download_and_extract "x64" "$FOLDER_OS-x64"
    ;;
  all)
    if [ "$API_OS" = "windows" ]; then
      download_and_extract "x64" "$FOLDER_OS-x64"
    else
      download_and_extract "aarch64" "$FOLDER_OS-arm64"
      download_and_extract "x64" "$FOLDER_OS-x64"
    fi
    ;;
  *)
    echo "Usage: $0 [arm64|x64|all]" >&2
    exit 1
    ;;
esac

echo "==> JRE setup complete."
