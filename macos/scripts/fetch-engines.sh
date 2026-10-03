#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor"
COMMON="$ROOT/packaging/common"
mkdir -p "$VENDOR" "$COMMON"

# Versions and digests below are release inputs, not user overrides. A build
# must be reproducible and must not silently package a beta from the environment.
SING_BOX_VERSION="1.13.21"
case "$(uname -m)" in
  arm64)
    SING_BOX_ARCH="arm64"
    SING_BOX_ARCHIVE_SHA256="62bca85bf08b9145288729cf010c98ea9877b8086f7369cde9e127012d509424"
    ;;
  x86_64)
    SING_BOX_ARCH="amd64"
    SING_BOX_ARCHIVE_SHA256="61093d79211a6ae7b707d30f07be35b1167ca8366bf0dbc06ee5fb35c90dc9e8"
    ;;
  *)
    echo "Unsupported macOS architecture: $(uname -m)" >&2
    exit 1
    ;;
esac
SING_BOX_URL="https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-darwin-${SING_BOX_ARCH}.tar.gz"
TRUST_VERSION="1.0.49"
TRUST_URL="https://github.com/TrustTunnel/TrustTunnelClient/releases/download/v${TRUST_VERSION}/trusttunnel_client-v${TRUST_VERSION}-macos-universal.tar.gz"
TRUST_ARCHIVE_SHA256="f2dab732d17a885dcc4c81831fa4b263db250f5bea8a151416b518e936979c64"
GEOIP_COMMIT="b9c5e675b4d5359d4b47f4434fa7ae77e9991306"
GEOSITE_COMMIT="11fb9814c7de626956aab504f83e066e70b250d4"
GEOIP_URL="https://raw.githubusercontent.com/SagerNet/sing-geoip/${GEOIP_COMMIT}/geoip-ru.srs"
GEOSITE_URL="https://raw.githubusercontent.com/SagerNet/sing-geosite/${GEOSITE_COMMIT}/geosite-category-ru.srs"
GEOIP_SHA256="1a8115af741918ff24b37b87d3c6da21eccabc58f1eec059e461dca8bac16ff7"
GEOIP_JSON_SHA256="ad921e489713e5a837417e3a0f5ed5ce07d9f8ffe9aeecf47c13ee397bff0d2b"
GEOSITE_SHA256="c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870"
SING_BOX_BINARY_SHA256_ARM64="c71877673f3f444a11b3197b5f4c8e3954afb1341ddcf7ea8068f3bf6b187e12"
SING_BOX_BINARY_SHA256_AMD64="a14b86ae891ce92a740cc1f0647a3c2c5a6b951bf60a47294d0311099626cf5c"
TRUST_BINARY_SHA256="dbadec0019352f7164adb618c3e076cf902d102870bbb3a7896f784886ef1573"

verify_sha256() {
  local expected="$1"
  local file="$2"
  printf '%s  %s\n' "$expected" "$file" | shasum -a 256 -c -
}

if [ "$SING_BOX_ARCH" = "arm64" ]; then
  SING_BOX_BINARY_SHA256="$SING_BOX_BINARY_SHA256_ARM64"
else
  SING_BOX_BINARY_SHA256="$SING_BOX_BINARY_SHA256_AMD64"
fi

curl -fsSL "$SING_BOX_URL" -o "$VENDOR/sing-box.tgz"
verify_sha256 "$SING_BOX_ARCHIVE_SHA256" "$VENDOR/sing-box.tgz"
rm -rf "$VENDOR/sing-box-extract"
mkdir -p "$VENDOR/sing-box-extract"
tar -xzf "$VENDOR/sing-box.tgz" -C "$VENDOR/sing-box-extract"
SING_BOX_BINARY="$(find "$VENDOR/sing-box-extract" -type f -name 'sing-box' -perm +111 -print -quit)"
test -n "$SING_BOX_BINARY"
cp "$SING_BOX_BINARY" "$COMMON/sing-box"
chmod 755 "$COMMON/sing-box"
verify_sha256 "$SING_BOX_BINARY_SHA256" "$COMMON/sing-box"

curl -fsSL "$TRUST_URL" -o "$VENDOR/trusttunnel.tgz"
verify_sha256 "$TRUST_ARCHIVE_SHA256" "$VENDOR/trusttunnel.tgz"
rm -rf "$VENDOR/trusttunnel-extract"
mkdir -p "$VENDOR/trusttunnel-extract"
tar -xzf "$VENDOR/trusttunnel.tgz" -C "$VENDOR/trusttunnel-extract"
TRUST_BINARY="$(find "$VENDOR/trusttunnel-extract" -type f \( -name 'trusttunnel_client' -o -name 'trusttunnel' \) -print -quit)"
test -n "$TRUST_BINARY"
cp "$TRUST_BINARY" "$COMMON/trusttunnel_client"
chmod 755 "$COMMON/trusttunnel_client"
verify_sha256 "$TRUST_BINARY_SHA256" "$COMMON/trusttunnel_client"

mkdir -p "$COMMON/geo"
curl -fsSL "$GEOIP_URL" -o "$COMMON/geo/geoip-ru.srs"
curl -fsSL "$GEOSITE_URL" -o "$COMMON/geo/geosite-category-ru.srs"
verify_sha256 "$GEOIP_SHA256" "$COMMON/geo/geoip-ru.srs"
verify_sha256 "$GEOSITE_SHA256" "$COMMON/geo/geosite-category-ru.srs"
"$COMMON/sing-box" rule-set decompile "$COMMON/geo/geoip-ru.srs" \
  --output "$COMMON/geo/geoip-ru.json"
verify_sha256 "$GEOIP_JSON_SHA256" "$COMMON/geo/geoip-ru.json"
"$COMMON/sing-box" rule-set decompile "$COMMON/geo/geosite-category-ru.srs" \
  --output "$COMMON/geo/geosite-category-ru.json"
test -s "$COMMON/geo/geosite-category-ru.json"

file "$COMMON/sing-box" | grep -q 'Mach-O'
file "$COMMON/trusttunnel_client" | grep -q 'Mach-O'
shasum -a 256 \
  "$COMMON/sing-box" \
  "$COMMON/trusttunnel_client" \
  "$COMMON/geo/geoip-ru.srs" \
  "$COMMON/geo/geoip-ru.json" \
  "$COMMON/geo/geosite-category-ru.srs" \
  "$COMMON/geo/geosite-category-ru.json" | tee "$VENDOR/BUNDLED_SHA256SUMS"
cat > "$VENDOR/BUNDLED_UPSTREAM.json" <<EOF
{
  "sing-box": {
    "version": "${SING_BOX_VERSION}",
    "url": "${SING_BOX_URL}",
    "archiveSha256": "${SING_BOX_ARCHIVE_SHA256}",
    "binarySha256": "${SING_BOX_BINARY_SHA256}",
    "license": "GPL-3.0-or-later"
  },
  "trusttunnel_client": {
    "version": "${TRUST_VERSION}",
    "url": "${TRUST_URL}",
    "archiveSha256": "${TRUST_ARCHIVE_SHA256}",
    "binarySha256": "${TRUST_BINARY_SHA256}",
    "license": "Apache-2.0"
  },
  "geoip-ru": {
    "commit": "${GEOIP_COMMIT}",
    "url": "${GEOIP_URL}",
    "sha256": "${GEOIP_SHA256}",
    "derivedJsonSha256": "${GEOIP_JSON_SHA256}",
    "derivedBy": "sing-box ${SING_BOX_VERSION} rule-set decompile"
  },
  "geosite-category-ru": {
    "commit": "${GEOSITE_COMMIT}",
    "url": "${GEOSITE_URL}",
    "sha256": "${GEOSITE_SHA256}",
    "derivedBy": "sing-box ${SING_BOX_VERSION} rule-set decompile"
  }
}
EOF
echo "Engines installed into $COMMON"
