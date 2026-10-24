#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD_ROOT="$ROOT/build/linux"
CLASSES_DIR="$BUILD_ROOT/classes"
INPUT_DIR="$BUILD_ROOT/input"
DIST_DIR="$BUILD_ROOT/dist"
APP_NAME="MeteoBettiUltimateOS"
APP_VERSION="${APP_VERSION:-1.0.0}"
APP_IMAGE="$DIST_DIR/$APP_NAME"
ARCHIVE_PATH="$DIST_DIR/$APP_NAME-linux-$(uname -m).tar.gz"

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "Questo script va eseguito su Linux." >&2
    exit 1
fi

for tool in javac jar jpackage tar; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Comando richiesto non trovato: $tool (installa un JDK 25)." >&2
        exit 1
    fi
done

rm -rf "$CLASSES_DIR" "$INPUT_DIR"
mkdir -p "$CLASSES_DIR" "$INPUT_DIR" "$DIST_DIR"
javac -encoding UTF-8 -d "$CLASSES_DIR" "$ROOT/MeteoBettiUltimateOS.java"
jar --create --file "$INPUT_DIR/$APP_NAME.jar" \
    -C "$CLASSES_DIR" . \
    -C "$ROOT" sfondo_sole.jpg \
    -C "$ROOT" sfondo_pioggia.jpg \
    -C "$ROOT" sfondo_nuvoloso.jpg

if [[ -d "$APP_IMAGE" ]]; then
    rm -rf "$APP_IMAGE"
fi

jpackage \
    --type app-image \
    --name "$APP_NAME" \
    --input "$INPUT_DIR" \
    --main-jar "$APP_NAME.jar" \
    --main-class MeteoBettiUltimateOS \
    --dest "$DIST_DIR" \
    --app-version "$APP_VERSION" \
    --vendor "MeteoBetti"

tar -czf "$ARCHIVE_PATH" -C "$DIST_DIR" "$APP_NAME"
echo "Pacchetto creato: $ARCHIVE_PATH"
