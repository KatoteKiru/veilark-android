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
SIZE="$(value size)"
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
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" \
  "$WORK/Applications/Veilark.app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $((BUILD - 1))" \
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
  --size "$SIZE" \
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
[ "$REJECTED_VERSION" = "$VERSION" ] || { echo "Rejected update changed the app" >&2; exit 1; }

# A failure after the image is mounted (installed build is not older) must still
# detach the image: exit() skips Swift `defer`, so the updater detaches explicitly.
mkdir -p "$WORK/Current"
/usr/bin/ditto "$WORK/Applications/Veilark.app" "$WORK/Current/Veilark.app"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD" "$WORK/Current/Veilark.app/Contents/Info.plist"
cp "$DMG" "$WORK/not-newer.dmg"
sleep 0.1 &
STALE_PARENT_PID=$!
if "$UPDATER" \
  --dmg "$WORK/not-newer.dmg" \
  --sha256 "$SHA256" \
  --size "$SIZE" \
  --version "$VERSION" \
  --build "$BUILD" \
  --architecture "$ARCHITECTURE" \
  --url "$URL" \
  --notes "$NOTES" \
  --signature "$SIGNATURE" \
  --current-app "$WORK/Current/Veilark.app" \
  --pid "$STALE_PARENT_PID" \
  --relaunch false; then
  echo "Updater accepted a build that is not newer" >&2
  exit 1
fi
if hdiutil info | grep -F "$(basename "$WORK")/not-newer.dmg" >/dev/null; then
  echo "Updater left the update image attached after a post-mount failure" >&2
  exit 1
fi

sleep 0.3 &
PARENT_PID=$!
"$UPDATER" \
  --dmg "$WORK/update.dmg" \
  --sha256 "$SHA256" \
  --size "$SIZE" \
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
INSTALLED_BUILD="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' \
  "$WORK/Applications/Veilark.app/Contents/Info.plist")"
[ "$INSTALLED_BUILD" = "$BUILD" ] || {
  echo "Updater installed build $INSTALLED_BUILD instead of $BUILD" >&2
  exit 1
}
[ ! -e "$WORK/update.dmg" ] || { echo "Updater did not remove the consumed DMG" >&2; exit 1; }
if hdiutil info | grep -F "$(basename "$WORK")/update.dmg" >/dev/null; then
  echo "Updater left the update image attached after success" >&2
  exit 1
fi
echo "Verified in-app update to $VERSION build $BUILD without touching /Applications."
