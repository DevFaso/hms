#!/usr/bin/env bash
# Prints the build number a mobile release uses. The ONE place the two
# schemes are defined and justified; both workflows call it.
#
#   build-number.sh android [--now EPOCH]   seconds since 2026-01-01 UTC
#   build-number.sh ios     [--now EPOCH]   UTC yyyymmddHHMM
#
# Both have to rise on every publish: Play rejects a version code it has
# already accepted, and App Store Connect a build number it has already seen
# for the marketing version. github.run_number does not rise reliably (a
# re-run keeps it, and it restarts at 1 when the workflow file is renamed),
# so both are read off the clock.
#
# They differ on purpose:
#   - Android: Play caps a version code at 2100000000, and yyyymmddHHMM
#     (already 12 digits) is far past it. Seconds since 2026-01-01 stays under
#     the cap for about 66 years. Seconds, not minutes, because the
#     concurrency group is keyed on the ref: two dispatches on different refs
#     run in parallel and can reach this in the same minute, and the second
#     bundle would be refused after its whole build. This narrows the
#     collision window rather than closing it; the code is still read off the
#     runner clock, not off Play's state.
#   - iOS: CFBundleVersion has no such cap, and a readable timestamp is what
#     App Store Connect and TestFlight show a tester. Two uploads in one minute
#     would collide; the TestFlight dispatch is manual and rare.
#
# --now exists for the tests; a release never passes it.
set -euo pipefail

PLAY_EPOCH=1767225600   # 2026-01-01T00:00:00Z
PLAY_MAX=2100000000

platform=${1:-}
now=
if [ "${2:-}" = "--now" ]; then
  now=${3:-}
fi
if [ -z "$now" ]; then
  now=$(date -u +%s)
fi
case "$now" in
  ''|*[!0-9]*) echo "::error::build-number: --now must be a Unix time in seconds" >&2; exit 2 ;;
esac

case "$platform" in
  android)
    code=$((now - PLAY_EPOCH))
    if [ "$code" -le 0 ] || [ "$code" -gt "$PLAY_MAX" ]; then
      echo "::error::build-number: the Android version code is outside Play's range (1..$PLAY_MAX)" >&2
      exit 1
    fi
    echo "$code"
    ;;
  ios)
    # GNU date takes -d @EPOCH, BSD (the macOS runner) takes -r EPOCH.
    if out=$(date -u -d "@$now" +%Y%m%d%H%M 2>/dev/null); then
      echo "$out"
    else
      date -u -r "$now" +%Y%m%d%H%M
    fi
    ;;
  *)
    echo "::error::build-number: expected android or ios" >&2
    exit 2
    ;;
esac
