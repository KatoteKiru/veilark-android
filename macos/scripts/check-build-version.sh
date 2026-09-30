#!/usr/bin/env bash
# Print and assert the Mach-O deployment target (minos) and SDK of packaged binaries.
#
#   check-build-version.sh <app bundle>
#
# Environment:
#   VEILARK_MACOS_DEPLOYMENT_TARGET  expected minos of Veilark's own binaries (default 12.0)
#   VEILARK_REQUIRE_SDK_MAJOR        when set (e.g. 26), Veilark's own binaries must be
#                                    linked against at least this SDK major version
#
# The jpackage launcher comes from the JDK; stamp-launcher-sdk.sh re-stamps it with the
# build SDK because AppKit decides "linked on or after" behaviour (Liquid Glass system
# chrome) from the main executable. It is asserted only when VEILARK_REQUIRE_SDK_MAJOR is
# set, and reported otherwise.
set -euo pipefail

APP="${1:?app bundle required}"
TARGET="${VEILARK_MACOS_DEPLOYMENT_TARGET:-12.0}"
REQUIRE_SDK="${VEILARK_REQUIRE_SDK_MAJOR:-}"
RESOURCES="$APP/Contents/app/resources"

fail() { echo "Build version check failed: $*" >&2; exit 1; }

# Prints "<minos> <sdk>" for the first architecture slice, from LC_BUILD_VERSION or the
# older LC_VERSION_MIN_MACOSX load command.
build_version() {
  otool -l "$1" | awk '
    /cmd LC_BUILD_VERSION/ { mode = "build"; next }
    /cmd LC_VERSION_MIN_MACOSX/ { mode = "legacy"; next }
    /cmd / { mode = "" }
    mode == "build" && $1 == "minos" { minos = $2 }
    mode == "legacy" && $1 == "version" { minos = $2 }
    mode != "" && $1 == "sdk" { print minos, $2; exit }
  '
}

LAUNCHER_NAME="$(/usr/libexec/PlistBuddy -c 'Print CFBundleExecutable' "$APP/Contents/Info.plist")"
PLIST_MIN="$(/usr/libexec/PlistBuddy -c 'Print LSMinimumSystemVersion' "$APP/Contents/Info.plist" 2>/dev/null || true)"
echo "Info.plist LSMinimumSystemVersion: ${PLIST_MIN:-<absent>}"
[ "$PLIST_MIN" = "$TARGET" ] || fail "LSMinimumSystemVersion is '${PLIST_MIN:-absent}', expected $TARGET"

LAUNCHER="$APP/Contents/MacOS/$LAUNCHER_NAME"
[ -f "$LAUNCHER" ] || fail "launcher $LAUNCHER_NAME missing"
read -r LAUNCHER_MIN LAUNCHER_SDK <<<"$(build_version "$LAUNCHER")"
echo "launcher $LAUNCHER_NAME: minos=${LAUNCHER_MIN:-?} sdk=${LAUNCHER_SDK:-?} ($(lipo -archs "$LAUNCHER"))"
if [ -n "$REQUIRE_SDK" ]; then
  [ "${LAUNCHER_SDK%%.*}" -ge "$REQUIRE_SDK" ] 2>/dev/null || fail "launcher was linked against SDK ${LAUNCHER_SDK:-unknown}, need >= $REQUIRE_SDK"
  [ "$LAUNCHER_MIN" = "$TARGET" ] || fail "launcher deployment target is ${LAUNCHER_MIN:-unknown}, expected $TARGET"
fi

for binary in libveilark-chrome.dylib veilark-helper veilark-updater; do
  path="$RESOURCES/$binary"
  [ -f "$path" ] || fail "$binary missing"
  read -r MINOS SDK <<<"$(build_version "$path")"
  echo "$binary: minos=${MINOS:-?} sdk=${SDK:-?} ($(lipo -archs "$path"))"
  [ "$MINOS" = "$TARGET" ] || fail "$binary deployment target is ${MINOS:-unknown}, expected $TARGET"
  if [ -n "$REQUIRE_SDK" ]; then
    [ "${SDK%%.*}" -ge "$REQUIRE_SDK" ] 2>/dev/null || fail "$binary was linked against SDK ${SDK:-unknown}, need >= $REQUIRE_SDK"
  fi
done
echo "Verified deployment target $TARGET${REQUIRE_SDK:+ and SDK >= $REQUIRE_SDK}."
