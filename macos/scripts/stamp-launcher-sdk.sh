#!/usr/bin/env bash
# Re-stamp the jpackage launcher (Contents/MacOS/<CFBundleExecutable>) with the build
# SDK so AppKit's "linked on or after" checks, which read the main executable's
# LC_BUILD_VERSION, enable macOS 26 Liquid Glass system chrome. The launcher binary comes
# from the JDK and is otherwise reported as built with an older SDK.
#
#   stamp-launcher-sdk.sh <Veilark.app> <deployment target, e.g. 12.0>
#
# The bundle is then re-signed with the identity that signed it before: ad-hoc stays
# ad-hoc; a real identity must be supplied in VEILARK_MACOS_SIGNING_IDENTITY (never
# silently downgraded). Entitlements, requirements, flags and hardened runtime are kept.
set -euo pipefail

APP="${1:?app bundle required}"
TARGET="${2:?deployment target required}"
fail() { echo "Launcher SDK stamping failed: $*" >&2; exit 1; }

[ "$(uname -s)" = Darwin ] || fail "must run on macOS"
PLIST="$APP/Contents/Info.plist"
EXECUTABLE="$(/usr/libexec/PlistBuddy -c 'Print CFBundleExecutable' "$PLIST")"
LAUNCHER="$APP/Contents/MacOS/$EXECUTABLE"
[ -f "$LAUNCHER" ] || fail "launcher $EXECUTABLE missing"

SDK="$(xcrun --sdk macosx --show-sdk-version)"
case "$SDK" in
  [0-9]*.[0-9]*) ;;
  [0-9]*) SDK="$SDK.0" ;;
  *) fail "unexpected SDK version '$SDK'" ;;
esac

current_sdk() {
  otool -l "$LAUNCHER" | awk '/cmd LC_BUILD_VERSION/{f=1} f&&$1=="sdk"{print $2; exit}'
}
BEFORE="$(current_sdk)"
older() { # older A B: A < B as dotted versions
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" = "$1" ]
}
if [ -n "$BEFORE" ] && ! older "$BEFORE" "$SDK"; then
  echo "Launcher already linked against SDK $BEFORE (build SDK $SDK); not stamped."
  exit 0
fi

# Identity that signed the bundle before we touch it.
SIGNATURE_INFO="$(codesign -dvv "$APP" 2>&1 || true)"
if grep -q '^Signature=adhoc' <<<"$SIGNATURE_INFO"; then
  IDENTITY="-"
elif grep -q '^Authority=' <<<"$SIGNATURE_INFO"; then
  IDENTITY="${VEILARK_MACOS_SIGNING_IDENTITY:-}"
  [ -n "$IDENTITY" ] || fail "bundle is signed by $(grep -m1 '^Authority=' <<<"$SIGNATURE_INFO"); set VEILARK_MACOS_SIGNING_IDENTITY to re-sign"
else
  IDENTITY="-" # unsigned: arm64 still needs at least an ad-hoc signature
fi

WORK="$(mktemp -d /tmp/veilark-stamp.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
# Without -arch, vtool rewrites every architecture slice.
vtool -set-build-version macos "$TARGET" "$SDK" -replace -output "$WORK/launcher" "$LAUNCHER"
chmod 755 "$WORK/launcher"
mv -f "$WORK/launcher" "$LAUNCHER"

SIGN=(codesign --force --sign "$IDENTITY" --preserve-metadata=identifier,entitlements,requirements,flags,runtime)
[ "$IDENTITY" = "-" ] || SIGN+=(--timestamp)
"${SIGN[@]}" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

AFTER="$(current_sdk)"
SIGNER="$IDENTITY"; [ "$SIGNER" != "-" ] || SIGNER="ad-hoc"
echo "Stamped launcher $EXECUTABLE ($(lipo -archs "$LAUNCHER")): sdk ${BEFORE:-?} -> $AFTER, minos $TARGET, signed $SIGNER."
[ "$AFTER" = "$SDK" ] || fail "launcher reports SDK $AFTER after stamping, expected $SDK"
