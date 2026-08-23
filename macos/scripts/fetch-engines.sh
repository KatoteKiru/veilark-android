#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor"
COMMON="$ROOT/packaging/common"
mkdir -p "$VENDOR" "$COMMON"

SING_BOX_VERSION="${SING_BOX_VERSION:-1.13.14}"
SING_BOX_URL="https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/sing-box-${SING_BOX_VERSION}-darwin-arm64.tar.gz"
TRUST_VERSION="${TRUST_TUNNEL_VERSION:-1.0.49}"
TRUST_URL="https://github.com/TrustTunnel/TrustTunnelClient/releases/download/v${TRUST_VERSION}/trusttunnel_client-v${TRUST_VERSION}-macos-universal.tar.gz"

curl -fsSL "$SING_BOX_URL" -o "$VENDOR/sing-box.tgz"
mkdir -p "$VENDOR/sing-box-extract"
tar -xzf "$VENDOR/sing-box.tgz" -C "$VENDOR/sing-box-extract"
find "$VENDOR/sing-box-extract" -type f -name 'sing-box' -perm +111 -exec cp {} "$COMMON/sing-box" \;
chmod 755 "$COMMON/sing-box"

curl -fsSL "$TRUST_URL" -o "$VENDOR/trusttunnel.tgz"
mkdir -p "$VENDOR/trusttunnel-extract"
tar -xzf "$VENDOR/trusttunnel.tgz" -C "$VENDOR/trusttunnel-extract"
find "$VENDOR/trusttunnel-extract" -type f \( -name 'trusttunnel_client' -o -name 'trusttunnel' \) -exec cp {} "$COMMON/trusttunnel_client" \;
chmod 755 "$COMMON/trusttunnel_client"

shasum -a 256 "$COMMON/sing-box" "$COMMON/trusttunnel_client" | tee "$VENDOR/SHA256SUMS"
cat > "$VENDOR/UPSTREAM.json" <<EOF
{
  "sing-box": {
    "version": "${SING_BOX_VERSION}",
    "url": "${SING_BOX_URL}",
    "license": "GPL-3.0-or-later"
  },
  "trusttunnel_client": {
    "version": "${TRUST_VERSION}",
    "url": "${TRUST_URL}",
    "license": "Apache-2.0"
  }
}
EOF
echo "Engines installed into $COMMON"
