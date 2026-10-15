#!/usr/bin/env bash
# Build the Android app on the Mac mini (the only machine with the SDK) and copy the APK back.
# Usage: scripts/build-android.sh [install]
set -euo pipefail
HOST=${BUILD_HOST:-macmini}
REMOTE=${REMOTE_DIR:-'~/Projects/watchbridge'}
root=$(cd "$(dirname "$0")/.." && pwd)
rsync -az --delete --exclude .git --exclude 'android/build' --exclude 'android/app/build' --exclude 'android/.gradle' \
  --exclude 'watch/build' --exclude 'watch/*.xcodeproj' "$root/" "$HOST:$REMOTE/"
ssh "$HOST" "cd $REMOTE/android && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew --no-daemon -q assembleDebug"
mkdir -p "$root/dist"
scp -q "$HOST:$REMOTE/android/app/build/outputs/apk/debug/app-debug.apk" "$root/dist/watchbridge-debug.apk"
echo "APK: $root/dist/watchbridge-debug.apk"
if [[ "${1:-}" == "install" ]]; then
  adb install -r "$root/dist/watchbridge-debug.apk"
fi
