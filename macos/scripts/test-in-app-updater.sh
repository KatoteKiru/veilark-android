#!/usr/bin/env bash
# Exercise the real native updater against a disposable installed app bundle.
# The test never touches /Applications and never requests administrator rights.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DMG="${1:?DMG path required}"
MANIFEST="${2:?manifest path required}"
UPDATER="$ROOT/build/updater/veilark-updater"

[ -x "$UPDATER" ] || { echo "Updater binary is missing" >&2; exit 1; }
hdiutil verify "$DMG" >/dev/null

value() {
  /usr/bin/plutil -extract "$1" raw -o - "$MANIFEST"
}

VERSION="$(value version)"
BUILD="$(value build)"
ARCHITECTURE="$(value architecture)"
URL="$(value url)"
SHA256="$(value sha256)"
NOTES="$(value notes)"
SIGNATURE="$(value signature)"

WORK="$(mktemp -d /tmp/veilark-updater-test.XXXXXX)"
MOUNT="$WORK/mount"
mkdir -p "$MOUNT" "$WORK/Applications"
cleanup() {
  hdiutil detach "$MOUNT" -quiet -force >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MOUNT" >/dev/null
/usr/bin/ditto "$MOUNT/Veilark.app" "$WORK/Applications/Veilark.app"
hdiutil detach "$MOUNT" -quiet -force >/dev/null
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString 1.0.1" \
  "$WORK/Applications/Veilark.app/Contents/Info.plist"
cp "$DMG" "$WORK/update.dmg"

if [ "${SIGNATURE:0:1}" = "A" ]; then
  BAD_SIGNATURE="B${SIGNATURE:1}"
else
  BAD_SIGNATURE="A${SIGNATURE:1}"
fi
cp "$DMG" "$WORK/rejected.dmg"
if "$UPDATER" \
  --dmg "$WORK/rejected.dmg" \
  --sha256 "$SHA256" \
  --version "$VERSION" \
  --build "$BUILD" \
  --architecture "$ARCHITECTURE" \
  --url "$URL" \
  --notes "$NOTES" \
  --signature "$BAD_SIGNATURE" \
  --current-app "$WORK/Applications/Veilark.app" \
  --pid "$$" \
  --relaunch false; then
  echo "Updater accepted a modified manifest signature" >&2
  exit 1
fi
REJECTED_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' \
  "$WORK/Applications/Veilark.app/Contents/Info.plist")"
[ "$REJECTED_VERSION" = "1.0.1" ] || { echo "Rejected update changed the app" >&2; exit 1; }

sleep 0.3 &
PARENT_PID=$!
"$UPDATER" \
  --dmg "$WORK/update.dmg" \
  --sha256 "$SHA256" \
  --version "$VERSION" \
  --build "$BUILD" \
  --architecture "$ARCHITECTURE" \
  --url "$URL" \
  --notes "$NOTES" \
  --signature "$SIGNATURE" \
  --current-app "$WORK/Applications/Veilark.app" \
  --pid "$PARENT_PID" \
  --relaunch false

INSTALLED_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' \
  "$WORK/Applications/Veilark.app/Contents/Info.plist")"
[ "$INSTALLED_VERSION" = "$VERSION" ] || {
  echo "Updater installed $INSTALLED_VERSION instead of $VERSION" >&2
  exit 1
}
[ ! -e "$WORK/update.dmg" ] || { echo "Updater did not remove the consumed DMG" >&2; exit 1; }
echo "Verified in-app update from 1.0.1 to $VERSION without touching /Applications."
