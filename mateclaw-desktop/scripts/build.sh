#!/usr/bin/env bash
#
# scripts/build.sh — Build the MateClaw Spring Boot backend JAR and place it
# at resources/app.jar so electron-builder can bundle it into the desktop app.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_ROOT/.." && pwd)"
SERVER_DIR="$REPO_ROOT/mateclaw-server"
RESOURCES_DIR="$PROJECT_ROOT/resources"

echo "==> Building mateclaw-server JAR from $REPO_ROOT"

# Build the Spring Boot fat JAR and required in-repo Maven modules.
# Running from the root reactor is required because mateclaw-server depends on
# mateclaw-plugin-api, which is part of this repository and is not published to
# Maven Central.
cd "$REPO_ROOT"
mvn -pl mateclaw-server -am clean package -DskipTests -Dmaven.test.skip=true -q

# Locate the built JAR
JAR_FILE=$(ls "$SERVER_DIR"/target/mateclaw-server-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
  echo "ERROR: Could not find built JAR in $SERVER_DIR/target/"
  exit 1
fi

echo "==> Copying $JAR_FILE → $RESOURCES_DIR/app.jar"
mkdir -p "$RESOURCES_DIR"
cp "$JAR_FILE" "$RESOURCES_DIR/app.jar"

echo "==> Done. JAR size: $(du -h "$RESOURCES_DIR/app.jar" | cut -f1)"
