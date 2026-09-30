#!/usr/bin/env bash
# Post-package regression check. It validates inventory and image integrity.
# Signing and notarization stay explicit release credentials gates.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DMG_DIR="$ROOT/build/compose/binaries/main/dmg"
DMG="$(find "$DMG_DIR" -maxdepth 1 -type f -name '*.dmg' -print -quit 2>/dev/null || true)"
[ -n "$DMG" ] || { echo "No DMG was produced" >&2; exit 1; }

hdiutil verify "$DMG"
MOUNT="$(mktemp -d /tmp/veilark-dmg.XXXXXX)"
CHROME_CHECK="$(mktemp -d /tmp/veilark-chrome-check.XXXXXX)"
cleanup() {
  hdiutil detach "$MOUNT" -quiet -force >/dev/null 2>&1 || true
  rmdir "$MOUNT" >/dev/null 2>&1 || true
  rm -f "$CHROME_CHECK/source.dylib" "$CHROME_CHECK/packaged.dylib"
  rmdir "$CHROME_CHECK" >/dev/null 2>&1 || true
}
trap cleanup EXIT
hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MOUNT" >/dev/null

APP="$(find "$MOUNT" -maxdepth 1 -type d -name 'Veilark.app' -print -quit)"
[ -n "$APP" ] || { echo "Veilark.app missing from DMG" >&2; exit 1; }
ICON_NAME="$(/usr/libexec/PlistBuddy -c 'Print CFBundleIconFile' "$APP/Contents/Info.plist")"
case "$ICON_NAME" in
  *.icns) ;;
  *) ICON_NAME="$ICON_NAME.icns" ;;
esac
ICON="$APP/Contents/Resources/$ICON_NAME"
[ -f "$ICON" ] || { echo "Packaged application icon missing" >&2; exit 1; }
cmp "$ROOT/build/branding/Veilark.icns" "$ICON" || {
  echo "Packaged application icon differs from generated brand asset" >&2; exit 1;
}
RESOURCES="$APP/Contents/app/resources"
for asset in veilark-helper veilark-updater libveilark-chrome.dylib sing-box trusttunnel_client geo/geoip-ru.srs geo/geoip-ru.json geo/geosite-category-ru.srs geo/geosite-category-ru.json; do
  [ -f "$RESOURCES/$asset" ] || { echo "Packaged asset missing: $asset" >&2; exit 1; }
done
[ -x "$RESOURCES/veilark-helper" ]
[ -x "$RESOURCES/veilark-updater" ]
[ -x "$RESOURCES/sing-box" ]
[ -x "$RESOURCES/trusttunnel_client" ]
# jpackage re-signs nested Mach-O files. Compare code after removing signatures
# from temporary copies, and independently verify the packaged signature.
codesign --verify --strict "$RESOURCES/libveilark-chrome.dylib"
cp "$ROOT/build/native/libveilark-chrome.dylib" "$CHROME_CHECK/source.dylib"
cp "$RESOURCES/libveilark-chrome.dylib" "$CHROME_CHECK/packaged.dylib"
codesign --remove-signature "$CHROME_CHECK/source.dylib"
codesign --remove-signature "$CHROME_CHECK/packaged.dylib"
python3 "$ROOT/scripts/normalize-macho-signature.py" "$CHROME_CHECK/source.dylib"
python3 "$ROOT/scripts/normalize-macho-signature.py" "$CHROME_CHECK/packaged.dylib"
cmp "$CHROME_CHECK/source.dylib" "$CHROME_CHECK/packaged.dylib" || {
  otool -D "$CHROME_CHECK/source.dylib" "$CHROME_CHECK/packaged.dylib"
  otool -L "$CHROME_CHECK/source.dylib" "$CHROME_CHECK/packaged.dylib"
  cmp -l "$CHROME_CHECK/source.dylib" "$CHROME_CHECK/packaged.dylib" | head -12 || true
  echo "Packaged native chrome differs from compiled library" >&2; exit 1;
}
bash "$ROOT/scripts/check-build-version.sh" "$APP"
echo "Verified packaged DMG inventory: $(basename "$DMG")"
