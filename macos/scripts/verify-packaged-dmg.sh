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
cleanup() {
  hdiutil detach "$MOUNT" -quiet -force >/dev/null 2>&1 || true
  rmdir "$MOUNT" >/dev/null 2>&1 || true
}
trap cleanup EXIT
hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MOUNT" >/dev/null

APP="$(find "$MOUNT" -maxdepth 1 -type d -name 'Veilark.app' -print -quit)"
[ -n "$APP" ] || { echo "Veilark.app missing from DMG" >&2; exit 1; }
RESOURCES="$APP/Contents/app/resources"
for asset in veilark-helper veilark-updater sing-box trusttunnel_client geo/geoip-ru.srs geo/geoip-ru.json geo/geosite-category-ru.srs geo/geosite-category-ru.json; do
  [ -f "$RESOURCES/$asset" ] || { echo "Packaged asset missing: $asset" >&2; exit 1; }
done
[ -x "$RESOURCES/veilark-helper" ]
[ -x "$RESOURCES/veilark-updater" ]
[ -x "$RESOURCES/sing-box" ]
[ -x "$RESOURCES/trusttunnel_client" ]
echo "Verified packaged DMG inventory: $(basename "$DMG")"
