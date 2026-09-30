#!/usr/bin/env bash
# Launch the packaged app (its real launcher, not the Gradle test JVM), let the native
# chrome attach, and assert what the RUNNING process reports: the main executable's SDK
# (what AppKit's linked-on-or-after checks read) and the native material/toolbar mode.
# Never starts a VPN; the app only opens its window.
#
#   assert-runtime-chrome.sh <Veilark.app> <min SDK major> [expected material] [expected toolbar]
set -euo pipefail

APP="${1:?app bundle required}"
MIN_SDK="${2:?minimum SDK major required}"
EXPECT_MATERIAL="${3:-}"
EXPECT_TOOLBAR="${4:-}"
EXECUTABLE="$(/usr/libexec/PlistBuddy -c 'Print CFBundleExecutable' "$APP/Contents/Info.plist")"
REPORT="$(mktemp /tmp/veilark-chrome-report.XXXXXX)"
rm -f "$REPORT"

VEILARK_NATIVE_CHROME_REPORT="$REPORT" "$APP/Contents/MacOS/$EXECUTABLE" >/tmp/veilark-runtime.log 2>&1 &
PID=$!
cleanup() { kill "$PID" >/dev/null 2>&1 || true; wait "$PID" 2>/dev/null || true; }
trap cleanup EXIT
for _ in $(seq 1 90); do
  [ -s "$REPORT" ] && break
  kill -0 "$PID" 2>/dev/null || { cat /tmp/veilark-runtime.log >&2; echo "Veilark exited before reporting" >&2; exit 1; }
  sleep 1
done
[ -s "$REPORT" ] || { tail -40 /tmp/veilark-runtime.log >&2; echo "No native chrome report within 90 s" >&2; exit 1; }
echo "Runtime report from $APP:"
cat "$REPORT"
value() { sed -n "s/^$1=//p" "$REPORT"; }
SDK="$(value programSdk)"
[ "${SDK%%.*}" -ge "$MIN_SDK" ] 2>/dev/null || { echo "Running launcher SDK is $SDK, need >= $MIN_SDK" >&2; exit 1; }
[ -z "$EXPECT_MATERIAL" ] || [ "$(value material)" = "$EXPECT_MATERIAL" ] || { echo "material $(value material) != $EXPECT_MATERIAL" >&2; exit 1; }
[ -z "$EXPECT_TOOLBAR" ] || [ "$(value toolbar)" = "$EXPECT_TOOLBAR" ] || { echo "toolbar $(value toolbar) != $EXPECT_TOOLBAR" >&2; exit 1; }
[ "$(value topInset)" -gt 0 ] || { echo "Compose content is not inset below the unified toolbar" >&2; exit 1; }
echo "Verified running packaged app: program SDK $SDK >= $MIN_SDK."
