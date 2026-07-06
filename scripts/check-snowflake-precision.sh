#!/usr/bin/env bash
set -euo pipefail

# Guard against accidentally converting Snowflake/Long identifiers to JS numbers.
# Backend serializes ids as strings; frontend code should preserve them as strings
# when calling APIs or binding dropdown values.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_SRC="$ROOT/mateclaw-ui/src"

if [[ ! -d "$UI_SRC" ]]; then
  echo "[snowflake] UI source directory not found: $UI_SRC" >&2
  exit 1
fi

# Keep this intentionally narrow to avoid flagging unrelated numeric parsing.
# Add allow comments if a future occurrence is provably not an id conversion.
PATTERN='\b(Number|parseInt|parseFloat)\s*\([^\n)]*(^|[^A-Za-z])(id|Id|ID|agentId|conversationId|workspaceId|kbId|skillId|toolId|modelId)[A-Za-z0-9_]*'
MATCHES="$(grep -RInE "$PATTERN" "$UI_SRC" --include='*.ts' --include='*.vue' || true)"

if [[ -n "$MATCHES" ]]; then
  echo "[snowflake] Potential precision-loss id conversion found:" >&2
  echo "$MATCHES" >&2
  echo "[snowflake] Keep Snowflake/Long ids as strings; avoid Number(id)/parseInt(id)." >&2
  exit 1
fi

echo "[snowflake] Precision guard passed."
