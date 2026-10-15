#!/usr/bin/env bash
# Generate the Xcode project with XcodeGen, build the watchOS app with automatic signing,
# and (optionally) install it on the paired Apple Watch through the iPhone.
# Usage: scripts/build-watch.sh [install]
# Needs: watch/.team containing your 10-character Apple Developer Team ID (git-ignored).
set -euo pipefail
HOST=${BUILD_HOST:-macmini}
REMOTE=${REMOTE_DIR:-'~/Projects/watchbridge'}
root=$(cd "$(dirname "$0")/.." && pwd)
team=$(cat "$root/watch/.team" 2>/dev/null || true)
[[ ${#team} -eq 10 ]] || { echo "put your Team ID in watch/.team" >&2; exit 1; }
rsync -az --delete --exclude .git --exclude 'android/build' --exclude 'android/app/build' --exclude 'android/.gradle' \
  --exclude 'watch/build' --exclude 'watch/*.xcodeproj' "$root/" "$HOST:$REMOTE/"
ssh "$HOST" "export PATH=/opt/homebrew/bin:\$PATH; cd $REMOTE/watch && xcodegen generate -q && \
  xcodebuild -project WatchBridge.xcodeproj -scheme WatchBridge -configuration Debug \
    -destination 'generic/platform=watchOS' -derivedDataPath build \
    -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
    DEVELOPMENT_TEAM=$team build 2>&1 | grep -E 'error|warning: |BUILD|Signing Identity' || true"
if [[ "${1:-}" == "install" ]]; then
  udid=$(ssh "$HOST" "xcrun devicectl list devices 2>/dev/null | awk '/Watch/ {print \$(NF-2)}' | head -1")
  [[ -n "$udid" ]] || { echo "no paired Apple Watch found by devicectl" >&2; exit 1; }
  ssh "$HOST" "cd $REMOTE/watch && xcrun devicectl device install app --device $udid build/Build/Products/Debug-watchos/WatchBridge.app"
fi
