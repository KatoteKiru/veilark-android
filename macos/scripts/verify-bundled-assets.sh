#!/usr/bin/env bash
# Validate the exact macOS assets that Gradle is allowed to put into a DMG.
# This has no network access: a package is rejected rather than repaired from
# an unpinned upstream artifact at build time.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMON="$ROOT/packaging/common"

fail() {
  echo "macOS package asset verification failed: $*" >&2
  exit 1
}

[ "$(uname -s)" = "Darwin" ] || fail "must run on macOS"
case "$(uname -m)" in
  arm64) SING_SHA="5b75c1dec19488675f725adc7a6e3a7301a553117af835dc47669b1fa918976b" ;;
  x86_64) SING_SHA="078164e43464f2282ae526151411320582c3e60a0294cec24a627edf205305a6" ;;
  *) fail "unsupported architecture: $(uname -m)" ;;
esac

TRUST_SHA="27554c0da2bf02f160de3d7be36896f4325bc823d0da4f4b88ea6aca92d0c4c8"
GEOIP_SHA="1a8115af741918ff24b37b87d3c6da21eccabc58f1eec059e461dca8bac16ff7"
GEOIP_JSON_SHA="ad921e489713e5a837417e3a0f5ed5ce07d9f8ffe9aeecf47c13ee397bff0d2b"
GEOSITE_SHA="c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870"

for asset in \
  "$COMMON/veilark-helper" \
  "$COMMON/veilark-updater" \
  "$COMMON/sing-box" \
  "$COMMON/trusttunnel_client" \
  "$COMMON/geo/geoip-ru.srs" \
  "$COMMON/geo/geoip-ru.json" \
  "$COMMON/geo/geosite-category-ru.srs" \
  "$COMMON/geo/geosite-category-ru.json"; do
  [ -f "$asset" ] || fail "missing $(basename "$asset"). Run scripts/fetch-engines.sh on this Mac."
done

[ -x "$COMMON/veilark-helper" ] || fail "veilark-helper is not executable"
[ -x "$COMMON/veilark-updater" ] || fail "veilark-updater is not executable"
[ -x "$COMMON/sing-box" ] || fail "sing-box is not executable"
[ -x "$COMMON/trusttunnel_client" ] || fail "trusttunnel_client is not executable"

echo "$SING_SHA  $COMMON/sing-box" | shasum -a 256 -c -
echo "$TRUST_SHA  $COMMON/trusttunnel_client" | shasum -a 256 -c -
echo "$GEOIP_SHA  $COMMON/geo/geoip-ru.srs" | shasum -a 256 -c -
echo "$GEOIP_JSON_SHA  $COMMON/geo/geoip-ru.json" | shasum -a 256 -c -
echo "$GEOSITE_SHA  $COMMON/geo/geosite-category-ru.srs" | shasum -a 256 -c -
DERIVED_JSON="$(mktemp)"
trap 'rm -f "$DERIVED_JSON"' EXIT
"$COMMON/sing-box" rule-set decompile "$COMMON/geo/geosite-category-ru.srs" \
  --output "$DERIVED_JSON"
cmp -s "$DERIVED_JSON" "$COMMON/geo/geosite-category-ru.json" || \
  fail "geosite JSON is not derived from the pinned SRS"

file "$COMMON/sing-box" | grep -q 'Mach-O' || fail "sing-box is not a Mach-O executable"
file "$COMMON/trusttunnel_client" | grep -q 'Mach-O' || fail "trusttunnel_client is not a Mach-O executable"
file "$COMMON/veilark-updater" | grep -q 'Mach-O' || fail "veilark-updater is not a Mach-O executable"
lipo -archs "$COMMON/veilark-updater" | tr ' ' '\n' | grep -qx "$(uname -m)" || fail "veilark-updater does not match $(uname -m)"
lipo -archs "$COMMON/sing-box" | tr ' ' '\n' | grep -qx "$(uname -m)" || fail "sing-box does not match $(uname -m)"
for architecture in arm64 x86_64; do
  lipo -archs "$COMMON/trusttunnel_client" | tr ' ' '\n' | grep -qx "$architecture" || fail "trusttunnel_client is not universal ($architecture absent)"
done

"$COMMON/sing-box" version | grep -q '^sing-box version 1\.13\.19' || fail "unexpected sing-box version"
echo "Verified macOS package assets for $(uname -m)."
