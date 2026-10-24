#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD_ROOT="$ROOT/build/macos"
APP_NAME="MeteoBettiUltimateOS"
APP_VERSION="${APP_VERSION:-1.0.0}"
PACKAGE_SUFFIX="${PACKAGE_SUFFIX:-macos}"
APP_IMAGE="$BUILD_ROOT/dist/$APP_NAME.app"
DMG_PATH="$BUILD_ROOT/dist/$APP_NAME-$PACKAGE_SUFFIX.dmg"
HELPER_APP="$APP_IMAGE/Contents/Helpers/LocationAgent.app"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "Questo script va eseguito su macOS." >&2
    exit 1
fi

rm -rf "$BUILD_ROOT/classes" "$BUILD_ROOT/input"
mkdir -p "$BUILD_ROOT/classes" "$BUILD_ROOT/input" "$BUILD_ROOT/dist"
javac -encoding UTF-8 -d "$BUILD_ROOT/classes" "$ROOT/MeteoBettiUltimateOS.java"
jar --create --file "$BUILD_ROOT/input/$APP_NAME.jar" \
    -C "$BUILD_ROOT/classes" . \
    -C "$ROOT" sfondo_sole.jpg \
    -C "$ROOT" sfondo_pioggia.jpg \
    -C "$ROOT" sfondo_nuvoloso.jpg

SDKROOT="$(xcrun --sdk macosx --show-sdk-path)"
if [[ -d /Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk ]]; then
    SDKROOT="/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"
fi

clang -isysroot "$SDKROOT" -mmacosx-version-min=13.0 -fobjc-arc \
    -framework CoreLocation -framework Foundation \
    "$ROOT/macos/LocationAgent.m" \
    -o "$BUILD_ROOT/LocationAgent"

if [[ -d "$APP_IMAGE" ]]; then
    rm -rf "$APP_IMAGE"
fi

jpackage \
    --type app-image \
    --name "$APP_NAME" \
    --input "$BUILD_ROOT/input" \
    --main-jar "$APP_NAME.jar" \
    --main-class MeteoBettiUltimateOS \
    --dest "$BUILD_ROOT/dist" \
    --app-version "$APP_VERSION" \
    --vendor "MeteoBetti"

mkdir -p "$HELPER_APP/Contents/MacOS"
cp "$BUILD_ROOT/LocationAgent" "$HELPER_APP/Contents/MacOS/LocationAgent"
cat > "$HELPER_APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>LocationAgent</string>
    <key>CFBundleIdentifier</key>
    <string>it.meteobetti.ultimateos.location</string>
    <key>CFBundleName</key>
    <string>MeteoBetti Location</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>NSLocationWhenInUseUsageDescription</key>
    <string>MeteoBetti usa la posizione del Mac per mostrarti il meteo del luogo in cui ti trovi.</string>
</dict>
</plist>
PLIST

/usr/libexec/PlistBuddy \
    -c "Add :NSLocationWhenInUseUsageDescription string MeteoBetti usa la posizione del Mac per mostrarti il meteo del luogo in cui ti trovi." \
    "$APP_IMAGE/Contents/Info.plist"

codesign --force --sign - "$HELPER_APP"
codesign --force --deep --sign - "$APP_IMAGE"
hdiutil create -volname "$APP_NAME" -srcfolder "$APP_IMAGE" \
    -ov -format UDZO "$DMG_PATH"

echo "App creata: $APP_IMAGE"
echo "Pacchetto creato: $DMG_PATH"
