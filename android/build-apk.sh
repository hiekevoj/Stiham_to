#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  fi
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$SCRIPT_DIR/.sdk" ]; then
    export ANDROID_HOME="$SCRIPT_DIR/.sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi

if [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}/opt/homebrew/bin:$PATH"

if [ ! -f "$SCRIPT_DIR/gradlew" ]; then
  echo "==> Generuji Gradle 8.9 Wrapper..."
  mkdir -p /tmp/gw-init
  cat > /tmp/gw-init/settings.gradle.kts <<EOF
rootProject.name = "gw-init"
EOF
  gradle -p /tmp/gw-init wrapper --gradle-version 8.9 --no-daemon
  cp -R /tmp/gw-init/gradlew /tmp/gw-init/gradlew.bat /tmp/gw-init/gradle "$SCRIPT_DIR/"
  chmod +x "$SCRIPT_DIR/gradlew"
fi

echo "==> Kompiluji Android aplikaci Stíhám to? (PMDP)..."
./gradlew assembleDebug --no-daemon

APK_SRC="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="$ROOT_DIR/StihamTo-Plzen.apk"

if [ -f "$APK_SRC" ]; then
  cp "$APK_SRC" "$APK_DEST"
  echo "✅ Hotovo! APK soubor je připraven:"
  ls -lh "$APK_DEST"
else
  echo "❌ APK soubor nebyl nalezen na $APK_SRC"
  exit 1
fi
