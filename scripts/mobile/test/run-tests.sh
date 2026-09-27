#!/usr/bin/env bash
# Tests for scripts/mobile/*. Runs on the macOS runner against the real
# plutil, and on Linux or Git Bash against fake_plutil.py.
#
#   bash scripts/mobile/test/run-tests.sh [SECTION...]
#
# SECTION is plist, dispatch, keystore or readback; none means all. The plist
# section is the slow one without a real plutil (each stand-in call starts a
# Python), so the Android job runs dispatch, keystore and readback on Linux,
# and the iOS job runs plist and dispatch on macOS against the real plutil.
#
# Output of the scripts under test is captured, not streamed: they print
# ::error:: lines on purpose, and on a runner those would become annotations
# on a green run. A failing test prints its captured output with `::` defused.
#
# The fixtures are full of literal $(SETTING) references on purpose:
# shellcheck disable=SC2016
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
M="$ROOT/scripts/mobile"
FIX="$HERE/fixtures"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Nothing under test may write into the real step's files.
unset GITHUB_STEP_SUMMARY GITHUB_OUTPUT GITHUB_ACTIONS GITHUB_ENV

SECTIONS=${*:-plist dispatch keystore readback}
for section in $SECTIONS; do
  case "$section" in
    plist|dispatch|keystore|readback) ;;
    *) echo "unknown section: $section" >&2; exit 2 ;;
  esac
done
wants() { case " $SECTIONS " in *" $1 "*) return 0 ;; esac; return 1; }

PLUTIL_KIND=unused
if ! wants plist; then
  :
elif [ "${MOBILE_TEST_FAKE_PLUTIL:-}" = 1 ] || ! command -v plutil >/dev/null 2>&1; then
  for py in python3 python; do
    if "$py" -c 'import plistlib' >/dev/null 2>&1; then
      FAKE_PLUTIL_PYTHON=$py
      break
    fi
  done
  if [ -z "${FAKE_PLUTIL_PYTHON:-}" ]; then
    echo "no plutil and no python to stand in for it" >&2
    exit 1
  fi
  # An exported function rather than a script on PATH: the `bash` that runs
  # each script under test inherits it, and on Git Bash resolving a shebang
  # script through PATH costs seconds per call.
  FAKE_PLUTIL_PY="$HERE/fake_plutil.py"
  export FAKE_PLUTIL_PYTHON FAKE_PLUTIL_PY
  plutil() { "$FAKE_PLUTIL_PYTHON" -S "$FAKE_PLUTIL_PY" "$@"; }
  export -f plutil
  PLUTIL_KIND=fake
else
  PLUTIL_KIND=real
fi

PASS=0
FAIL=0
OUT=
STATUS=

run() {
  OUT=$("$@" 2>&1)
  STATUS=$?
}

report_fail() {
  FAIL=$((FAIL + 1))
  echo "FAIL: $1"
  printf '%s\n' "$OUT" | sed 's/::/: :/g; s/^/    | /'
}

ok() {
  PASS=$((PASS + 1))
  echo "ok:   $1"
}

# expect NAME STATUS [contains...] ; a leading '!' on a needle means "must not contain"
expect() {
  local name=$1 want=$2 needle
  shift 2
  if [ "$STATUS" != "$want" ]; then
    report_fail "$name (exit $STATUS, wanted $want)"
    return
  fi
  for needle in "$@"; do
    case "$needle" in
      '!'*)
        if printf '%s' "$OUT" | grep -qF -- "${needle#!}"; then
          report_fail "$name (output contains '${needle#!}')"
          return
        fi
        ;;
      *)
        if ! printf '%s' "$OUT" | grep -qF -- "$needle"; then
          report_fail "$name (output lacks '$needle')"
          return
        fi
        ;;
    esac
  done
  ok "$name"
}

# Literal (non-regex) replace of every occurrence of $3 with $4.
mutate() {
  awk -v from="$3" -v to="$4" '
    { out = ""; rest = $0
      while ((i = index(rest, from)) > 0) { out = out substr(rest, 1, i - 1) to; rest = substr(rest, i + length(from)) }
      print out rest }
  ' "$1" > "$2"
}

# Removes a top-level <key>$3</key> line and the value line after it.
drop_key() {
  awk -v k="<key>$3</key>" 'skip { skip = 0; next } index($0, k) { skip = 1; next } { print }' "$1" > "$2"
}

if wants plist; then
  echo "== check-info-plist.sh (plutil: $PLUTIL_KIND)"
  CIP="$M/check-info-plist.sh"
  P="$FIX/project.yml"

  run bash "$CIP" --print-keys --project-yml "$P"
  want="CFBundleDisplayName NSFaceIDUsageDescription ITSAppUsesNonExemptEncryption CFBundleShortVersionString CFBundleVersion CFBundleURLTypes MEDIHUB_API_BASE_URL MEDIHUB_KEYCLOAK_ISSUER MEDIHUB_KEYCLOAK_SSO_ENABLED MEDIHUB_KEYCLOAK_REDIRECT_URI"
  if [ "$STATUS" = 0 ] && [ "$(printf '%s' "$OUT" | tr '\n' ' ' | sed 's/ $//')" = "$want" ]; then
    ok "derives exactly the top-level info keys from a fixture project.yml"
  else
    report_fail "derives exactly the top-level info keys from a fixture project.yml"
  fi

  run bash "$CIP" --print-keys --project-yml "$ROOT/patient-ios-app/project.yml"
  expect "derives the real project.yml's keys" 0 CFBundleVersion CFBundleShortVersionString MEDIHUB_API_BASE_URL \
    CFBundleURLTypes NSFaceIDUsageDescription ITSAppUsesNonExemptEncryption '!CFBundleTypeRole' '!CFBundleURLSchemes'

  { cat "$P"; printf '  Other:\n    info:\n      properties:\n        X: y\n'; } > "$TMP/two.yml"
  run bash "$CIP" --print-keys --project-yml "$TMP/two.yml"
  expect "refuses a project.yml with two info blocks" 2 "exactly one"

  printf 'name: X\ntargets:\n  A:\n    type: application\n' > "$TMP/none.yml"
  run bash "$CIP" --print-keys --project-yml "$TMP/none.yml"
  expect "refuses a project.yml with no info block" 2 "exactly one"

  S="$FIX/source.plist"
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P"
  expect "source: the generated plist passes" 0 "all 10 declared key(s)"

  mutate "$S" "$TMP/s.plist" '<string>$(CURRENT_PROJECT_VERSION)</string>' '<string>1</string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: a literal CFBundleVersion fails" 1 "CFBundleVersion must be the build-setting reference"

  # The generic rule above cannot catch this one: project.yml itself is the
  # literal, so the plist agrees with it. The pinned rule must.
  mutate "$P" "$TMP/literal.yml" 'CFBundleVersion: $(CURRENT_PROJECT_VERSION)' 'CFBundleVersion: "1"'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$TMP/literal.yml"
  expect "source: a literal CFBundleVersion fails even when project.yml declares it" 1 "CFBundleVersion must be the build-setting reference, never a literal"

  mutate "$S" "$TMP/s.plist" '<string>$(MEDIHUB_API_BASE_URL)</string>' '<string>https://literal.example.com</string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: a literal where project.yml has a reference fails" 1 "MEDIHUB_API_BASE_URL must be the build-setting reference"

  mutate "$S" "$TMP/s.plist" '<false/>' '<string>false</string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: a quoted export declaration fails" 1 "must be a boolean <false/>"

  mutate "$S" "$TMP/s.plist" '<false/>' '<true/>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: an export declaration of true only warns" 0 "::warning::" "non-exempt encryption"

  mutate "$S" "$TMP/s.plist" '<string>Use Face ID: quoted, with a colon</string>' '<string>   </string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: a blank Face ID purpose string fails" 1 "NSFaceIDUsageDescription is empty"

  drop_key "$S" "$TMP/s.plist" MEDIHUB_KEYCLOAK_ISSUER
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: a declared key missing from the plist fails" 1 "MEDIHUB_KEYCLOAK_ISSUER is declared in project.yml but missing"

  mutate "$S" "$TMP/s.plist" '<string>com.example.fixture</string>' '<string>other.scheme</string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: a redirect scheme CFBundleURLTypes does not register fails" 1 "not registered in CFBundleURLTypes"

  mutate "$S" "$TMP/s.plist" '<string>com.example.fixture</string>' '<string>not a scheme</string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P"
  expect "source: an invalid URL scheme entry fails" 1 "not a valid URL scheme"

  mkdir -p "$TMP/src-clean" "$TMP/src-crypto"
  printf 'import SwiftUI\nimport Security\n' > "$TMP/src-clean/A.swift"
  printf 'import SwiftUI\nimport CryptoKit\n' > "$TMP/src-crypto/A.swift"
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --sources "$TMP/src-clean"
  expect "source: no crypto import passes the scan" 0 "no crypto import in the app target"
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --sources "$TMP/src-crypto"
  expect "source: a first-party crypto import fails" 1 "imports a crypto framework"
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --sources "$TMP/nope"
  expect "source: a missing sources directory fails rather than scanning nothing" 1 "would have checked nothing"

  mkdir -p "$TMP/xc-good" "$TMP/xc-api" "$TMP/xc-sso" "$TMP/xc-redirect"
  # The issuer is written the way both real Config/*.xcconfig files write
  # it today: unescaped, so `//` truncates it to "https:".
  printf '%s\n' \
    '// A comment line.' \
    'MEDIHUB_API_BASE_URL = https:/$()/dev.example.com/api' \
    'MEDIHUB_KEYCLOAK_ISSUER = https://idp.example.com/realms/x' \
    'MEDIHUB_KEYCLOAK_SSO_ENABLED = 0' \
    'MEDIHUB_KEYCLOAK_REDIRECT_URI = com.example.fixture:/oauth2redirect' \
    > "$TMP/xc-good/Dev.xcconfig"
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --xcconfig-dir "$TMP/xc-good"
  expect "source: an unescaped // in an xcconfig issuer warns while SSO is off" 0 "::warning::" "MEDIHUB_KEYCLOAK_ISSUER from Dev.xcconfig"
  mutate "$TMP/xc-good/Dev.xcconfig" "$TMP/xc-api/Dev.xcconfig" 'https:/$()/dev' 'https://dev'
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --xcconfig-dir "$TMP/xc-api"
  expect "source: an xcconfig API URL cut short by // fails" 1 "MEDIHUB_API_BASE_URL in Dev.xcconfig does not resolve"
  mutate "$TMP/xc-good/Dev.xcconfig" "$TMP/xc-sso/Dev.xcconfig" 'SSO_ENABLED = 0' 'SSO_ENABLED = 1'
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --xcconfig-dir "$TMP/xc-sso"
  expect "source: SSO on with an issuer that is not a URL fails" 1 "not an https URL and SSO is enabled"
  mutate "$TMP/xc-good/Dev.xcconfig" "$TMP/xc-redirect/Dev.xcconfig" 'com.example.fixture:' 'com.example.other:'
  run bash "$CIP" --mode source --plist "$S" --project-yml "$P" --xcconfig-dir "$TMP/xc-redirect"
  expect "source: an xcconfig redirect scheme that is not registered fails" 1 "from Dev.xcconfig is not registered"

  # The repository's own Config/*.xcconfig: both API URLs resolve, and both
  # issuers are the unescaped-// case, which warns while SSO is off.
  mutate "$S" "$TMP/s.plist" '<string>com.example.fixture</string>' \
    '<string>com.example.fixture</string><string>com.bitnesttechs.hms.patient.native</string>'
  run bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P" --xcconfig-dir "$ROOT/patient-ios-app/Config"
  expect "source: the repository's Config/*.xcconfig resolve (issuers only warn)" 0 \
    "MEDIHUB_KEYCLOAK_ISSUER from Dev.xcconfig" "MEDIHUB_KEYCLOAK_ISSUER from Prod.xcconfig" '!MEDIHUB_API_BASE_URL in'

  printf '<plist><dict><key>x</key>' > "$TMP/broken.plist"
  run bash "$CIP" --mode source --plist "$TMP/broken.plist" --project-yml "$P"
  expect "a malformed plist is reported as malformed" 1 "cannot parse"

  # A value carrying a workflow command must never reach stdout.
  mutate "$S" "$TMP/s.plist" '<string>$(MEDIHUB_API_BASE_URL)</string>' '<string>x&#10;::warning::INJECTED</string>'
  : > "$TMP/summary.md"
  OUT=$(GITHUB_STEP_SUMMARY="$TMP/summary.md" bash "$CIP" --mode source --plist "$TMP/s.plist" --project-yml "$P" 2>&1)
  STATUS=$?
  expect "a plist value is never echoed to stdout" 1 "MEDIHUB_API_BASE_URL" '!INJECTED'
  if grep -q 'INJECTED' "$TMP/summary.md" && ! grep -q '^::' "$TMP/summary.md"; then
    ok "the rejected value goes to the step summary, on one quoted line"
  else
    OUT=$(cat "$TMP/summary.md"); report_fail "the rejected value goes to the step summary, on one quoted line"
  fi

  A="$FIX/archive.plist"
  run bash "$CIP" --mode archive --plist "$A" --project-yml "$P" --build-number 202609261200
  expect "archive: the resolved plist passes (the fixture issuer only warns)" 0 "all 10 declared key(s)" "::warning::"
  run bash "$CIP" --mode archive --plist "$A" --project-yml "$P" --build-number 202609261201
  expect "archive: a CFBundleVersion that is not this run's build number fails" 1 "CFBundleVersion is not the build number"
  run bash "$CIP" --mode archive --plist "$A" --project-yml "$P" --build-number abc
  expect "archive: a non-numeric --build-number is a usage error" 2 "must be the numeric build number"
  for bad in '' 'http://dev.example.com/api' 'https://' '$(MEDIHUB_API_BASE_URL)'; do
    mutate "$A" "$TMP/a.plist" '<string>https://dev.example.com/api</string>' "<string>$bad</string>"
    run bash "$CIP" --mode archive --plist "$TMP/a.plist" --project-yml "$P" --build-number 202609261200
    expect "archive: MEDIHUB_API_BASE_URL '$bad' fails" 1 "MEDIHUB_API_BASE_URL in the archive is not a non-empty https URL"
  done
  mutate "$A" "$TMP/a.plist" '<false/>' '<true/>'
  run bash "$CIP" --mode archive --plist "$TMP/a.plist" --project-yml "$P" --build-number 202609261200
  expect "archive: an export declaration of true fails" 1 "ITSEncryptionExportComplianceCode"
  mutate "$A" "$TMP/a.plist" '<string>com.example.fixture:/oauth2redirect</string>' '<string>com.example.other:/oauth2redirect</string>'
  run bash "$CIP" --mode archive --plist "$TMP/a.plist" --project-yml "$P" --build-number 202609261200
  expect "archive: a redirect URI whose scheme is not registered fails" 1 "from the archive is not registered"
  mutate "$A" "$TMP/a.plist" '<string>0</string>' '<string>1</string>'
  run bash "$CIP" --mode archive --plist "$TMP/a.plist" --project-yml "$P" --build-number 202609261200
  expect "archive: SSO on with a broken issuer fails" 1 "not an https URL and SSO is enabled"
  mutate "$A" "$TMP/a.plist" '<string>1.0.3</string>' '<string>1.0.3-beta</string>'
  run bash "$CIP" --mode archive --plist "$TMP/a.plist" --project-yml "$P" --build-number 202609261200
  expect "archive: a non-numeric marketing version fails" 1 "CFBundleShortVersionString is not a dotted numeric"
  mutate "$A" "$TMP/a.plist" '<string>Fixture</string>' '<string>$(PRODUCT_NAME)</string>'
  run bash "$CIP" --mode archive --plist "$TMP/a.plist" --project-yml "$P" --build-number 202609261200
  expect "archive: an unresolved build-setting reference fails" 1 "CFBundleDisplayName reached the archive as an unresolved"
fi

if wants dispatch; then
  echo "== validate-choice.sh"
  VC="$M/validate-choice.sh"
  vc() { DISPATCH_INPUT=$1 DISPATCH_VALUE=$2 DISPATCH_ALLOWED=$3 bash "$VC"; }
  run vc release_action stage_on_internal 'build_only stage_on_internal stage_and_submit'
  expect "accepts an allowed value" 0 "release_action=stage_on_internal"
  run vc release_action 'stage_on_internal stage_and_submit' 'build_only stage_on_internal stage_and_submit'
  expect "rejects two allowed values joined (the substring trap)" 1 "unknown release_action"
  run vc api_environment production 'dev prod'
  expect "rejects api_environment=production" 1 "expected one of: dev prod"
  run vc api_environment '' 'dev prod'
  expect "rejects an empty value" 1 "unknown api_environment"
  run vc configuration "$(printf 'Release-Dev\n::warning::INJECTED')" 'Release-Dev Release-Prod'
  expect "never echoes a rejected value" 1 '!INJECTED'
  run vc configuration Release-Dev 'Release-Dev $(touch x)'
  expect "refuses an allowed list that is not plain tokens" 2 "plain tokens"
  run vc 'Bad Name' x 'x'
  expect "refuses an input name that is not an identifier" 2 "lower-case identifier"

  echo "== require-env.sh"
  RE="$M/require-env.sh"
  run env REQUIRED_NAMES='SECRET_A SECRET_B' ENVIRONMENT_LABEL=mobile-release SECRET_A=a SECRET_B=b bash "$RE"
  expect "passes when every secret is set" 0 "all 2 required secret(s)"
  run env REQUIRED_NAMES='SECRET_A SECRET_B SECRET_C' ENVIRONMENT_LABEL=mobile-release SECRET_B=hunter2 bash "$RE"
  expect "reports every missing secret on one line, and no value" 1 "unset in the mobile-release environment: SECRET_A SECRET_C" '!hunter2'
  run env REQUIRED_NAMES='lower_case' ENVIRONMENT_LABEL=mobile-release bash "$RE"
  expect "refuses a name that is not an upper-case identifier" 2 "upper-case identifiers"
  run env REQUIRED_NAMES='' ENVIRONMENT_LABEL=mobile-release bash "$RE"
  expect "refuses an empty list" 2 "no secret names"

  echo "== build-number.sh"
  BN="$M/build-number.sh"
  run bash "$BN" android --now $((1767225600 + 22714546))
  expect "android: seconds since 2026-01-01" 0 22714546
  run bash "$BN" android --now 1767225600
  expect "android: refuses a code of zero" 1 "outside Play's range"
  run bash "$BN" android --now $((1767225600 + 2100000001))
  expect "android: refuses a code past Play's cap" 1 "outside Play's range"
  run bash "$BN" ios --now 1790424720
  expect "ios: UTC yyyymmddHHMM" 0 202609261212
  run bash "$BN" windows
  expect "refuses an unknown platform" 2 "expected android or ios"

  echo "== android-release-plan.sh"
  AP="$M/android-release-plan.sh"
  plan() { RELEASE_ACTION=$1 API_ENVIRONMENT=$2 PLAY_APP_PUBLISHED=$3 bash "$AP"; }
  run plan build_only dev ''
  expect "build_only publishes nothing" 0 "publish=false" "send_for_review=false" "api_base_url=https://dev.e-keneya.com/api"
  run plan stage_on_internal prod ''
  expect "stage_on_internal publishes without review" 0 "publish=true" "send_for_review=false" "api_base_url=https://api.e-keneya.com/api"
  run plan stage_and_submit dev ''
  expect "stage_and_submit is refused before the first publish" 1 "PLAY_APP_PUBLISHED"
  run plan stage_and_submit dev TRUE
  expect "stage_and_submit needs exactly 'true'" 1 "PLAY_APP_PUBLISHED"
  run plan stage_and_submit dev true
  expect "stage_and_submit after the first publish sends for review" 0 "publish=true" "send_for_review=true"
  run plan stage_on_internal production ''
  expect "api_environment=production is refused, not mapped to dev" 1 "unknown api_environment"
  run plan ship_it dev ''
  expect "an unknown release_action is refused" 1 "unknown release_action"
fi

if wants keystore; then
  echo "== CheckKeystore.java"
  if command -v java >/dev/null 2>&1 && command -v keytool >/dev/null 2>&1; then
    CK="$M/CheckKeystore.java"
    keytool -genkeypair -keystore "$TMP/ks.jks" -storetype JKS -storepass storepw1 -keypass keypw1 \
      -alias upload -keyalg RSA -keysize 2048 -dname CN=test -validity 1 >/dev/null 2>&1
    keytool -genkeypair -keystore "$TMP/ks.p12" -storetype PKCS12 -storepass storepw2 \
      -alias upload -keyalg RSA -keysize 2048 -dname CN=test -validity 1 >/dev/null 2>&1
    ck() { STORE_PASSWORD=$2 KEY_ALIAS=$3 KEY_PASSWORD=$4 java "$CK" "$1"; }
    run ck "$TMP/ks.jks" storepw1 upload keypw1
    expect "keystore: JKS with the right four values opens" 0 "all verified"
    run ck "$TMP/ks.jks" wrong upload keypw1
    expect "keystore: a wrong store password is named" 1 "ANDROID_STORE_PASSWORD does not open" '!wrong'
    run ck "$TMP/ks.jks" storepw1 nosuchalias keypw1
    expect "keystore: a wrong alias is named, and not printed" 1 "ANDROID_KEY_ALIAS is not an entry" '!nosuchalias'
    run ck "$TMP/ks.jks" storepw1 upload wrongkey
    expect "keystore: a wrong key password is caught (keytool -list cannot)" 1 "ANDROID_KEY_PASSWORD does not unlock"
    run ck "$TMP/ks.p12" storepw2 upload storepw2
    expect "keystore: PKCS12 with the right values opens" 0 "all verified"
    run ck "$TMP/ks.p12" storepw2 upload wrongkey
    expect "keystore: PKCS12 with a wrong key password is caught" 1 "ANDROID_KEY_PASSWORD does not unlock"
    printf 'not a keystore' > "$TMP/garbage.jks"
    run ck "$TMP/garbage.jks" storepw1 upload keypw1
    expect "keystore: a file that is not a keystore is named" 1 "ANDROID_KEYSTORE_BASE64 does not decode"
  else
    echo "skip: java/keytool not on PATH"
  fi
fi

if wants readback; then
  echo "== play-track-readback.sh"
  PR="$M/play-track-readback.sh"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TMP/sa.pem" 2>/dev/null
  openssl pkey -in "$TMP/sa.pem" -pubout -out "$TMP/sa.pub" 2>/dev/null
  jq -n --rawfile key "$TMP/sa.pem" \
    '{type: "service_account", client_email: "ci@example.iam.gserviceaccount.com", private_key: $key, token_uri: "https://attacker.example.com/token"}' \
    > "$TMP/sa.json"

  fixture() {
    local dir="$TMP/play-$1"
    mkdir -p "$dir"
    echo '{"access_token":"ya29.fixture-token","expires_in":3599,"token_type":"Bearer"}' > "$dir/token.json"
    echo '{"id":"edit-123","expiryTimeSeconds":"1790000000"}' > "$dir/insert.json"
    printf '%s\n' "$2" > "$dir/track.json"
    echo "$dir"
  }
  readback() { READBACK_FIXTURE_LOG="$TMP/calls.log" bash "$PR" --credentials "$TMP/sa.json" --package com.bitnesttechs.hms.patient --track internal "$@"; }

  D=$(fixture found '{"track":"internal","releases":[{"name":"old","versionCodes":["13"],"status":"completed"},{"name":"new","versionCodes":["22714546"],"status":"completed"}]}')
  : > "$TMP/calls.log"
  OUT=$(READBACK_FIXTURE_ASSERTION_OUT="$TMP/assertion" readback --version-code 22714546 --expect-status completed --fixture "$D" 2>&1)
  STATUS=$?
  expect "readback: the new version code on the track passes" 0 "version code 22714546 is on the internal track (release status completed)" '!ya29.fixture-token'
  if grep -q '^DELETE https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.bitnesttechs.hms.patient/edits/edit-123$' "$TMP/calls.log" \
     && grep -q '^POST https://oauth2.googleapis.com/token$' "$TMP/calls.log" \
     && grep -q '^GET .*/edits/edit-123/tracks/internal$' "$TMP/calls.log"; then
    ok "readback: token from Google's endpoint (not the key file's token_uri), then insert, get, and the edit deleted"
  else
    OUT=$(cat "$TMP/calls.log"); report_fail "readback: token from Google's endpoint (not the key file's token_uri), then insert, get, and the edit deleted"
  fi
  # The assertion is a real RS256 JWT the service account's public key verifies.
  if [ -s "$TMP/assertion" ]; then
    jwt=$(cat "$TMP/assertion")
    input=${jwt%.*}
    sig=${jwt##*.}
    b64d() { local s; s=$(printf '%s' "$1" | tr '_-' '/+'); while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done; printf '%s' "$s" | openssl base64 -d -A; }
    b64d "$sig" > "$TMP/sig.bin"
    claims=$(b64d "$(printf '%s' "$input" | cut -d. -f2)")
    if printf '%s' "$input" | openssl dgst -sha256 -verify "$TMP/sa.pub" -signature "$TMP/sig.bin" >/dev/null 2>&1 \
       && [ "$(printf '%s' "$claims" | jq -r '.aud + " " + .scope + " " + .iss + " " + ((.exp - .iat)|tostring)')" = \
            "https://oauth2.googleapis.com/token https://www.googleapis.com/auth/androidpublisher ci@example.iam.gserviceaccount.com 600" ]; then
      ok "readback: the JWT assertion verifies with the key's public half and carries aud, scope, iss and a 10-minute life"
    else
      OUT="claims=$claims"; report_fail "readback: the JWT assertion verifies with the key's public half and carries aud, scope, iss and a 10-minute life"
    fi
  else
    OUT=; report_fail "readback: the fixture run produced an assertion to verify"
  fi

  D=$(fixture numbers '{"track":"internal","releases":[{"versionCodes":[22714546],"status":"completed"}]}')
  run readback --version-code 22714546 --fixture "$D"
  expect "readback: numeric versionCodes match too" 0 "is on the internal track"

  D=$(fixture missing '{"track":"internal","releases":[{"name":"old","versionCodes":["13"],"status":"completed"}]}')
  : > "$TMP/calls.log"
  run readback --version-code 22714546 --fixture "$D"
  expect "readback: a version code that is not on the track fails" 1 "is NOT on the internal track" "holds version code(s): 13"
  if grep -q '^DELETE ' "$TMP/calls.log"; then ok "readback: the edit is deleted on failure too"; else OUT=$(cat "$TMP/calls.log"); report_fail "readback: the edit is deleted on failure too"; fi

  D=$(fixture empty '{"track":"internal"}')
  run readback --version-code 22714546 --fixture "$D"
  expect "readback: an empty track fails" 1 "holds version code(s): none"

  D=$(fixture draft '{"track":"internal","releases":[{"versionCodes":["22714546"],"status":"draft"}]}')
  run readback --version-code 22714546 --expect-status completed --fixture "$D"
  expect "readback: the right code with the wrong status fails" 1 "release status draft, expected completed"

  D=$(fixture token401 '{}')
  printf '%s\n' '{"error":"invalid_grant","error_description":"::error::INJECTED\nsecond line"}' > "$D/token.json"
  echo 400 > "$D/token.status"
  : > "$TMP/calls.log"
  run readback --version-code 22714546 --fixture "$D"
  expect "readback: a refused token is reported with Google's reason, defused" 1 "the token request failed with HTTP 400" "invalid_grant" '!::error::INJECTED'
  if grep -q 'edits' "$TMP/calls.log"; then OUT=$(cat "$TMP/calls.log"); report_fail "readback: no edit is created without a token"; else ok "readback: no edit is created without a token"; fi

  D=$(fixture track404 '{"error":{"code":404,"status":"NOT_FOUND","message":"Track not found"}}')
  echo 404 > "$D/track.status"
  : > "$TMP/calls.log"
  run readback --version-code 22714546 --fixture "$D"
  expect "readback: a failed tracks.get fails, naming the call" 1 "edits.tracks.get for internal failed with HTTP 404" "NOT_FOUND Track not found"
  if grep -q '^DELETE ' "$TMP/calls.log"; then ok "readback: the edit is deleted after a failed get"; else OUT=$(cat "$TMP/calls.log"); report_fail "readback: the edit is deleted after a failed get"; fi

  echo '{"type":"authorized_user","client_id":"x"}' > "$TMP/user.json"
  run bash "$PR" --credentials "$TMP/user.json" --package com.bitnesttechs.hms.patient --track internal --version-code 1 --fixture "$D"
  expect "readback: credentials that are not a service-account key are refused" 1 "not a service-account JSON key"
  run readback --version-code 12a --fixture "$D"
  expect "readback: a non-numeric version code is a usage error" 2 "must be numeric"
  run readback --version-code 1 --expect-status shipped --fixture "$D"
  expect "readback: an unknown --expect-status is a usage error" 2 "--expect-status must be"
fi

echo
echo "mobile script tests: $PASS passed, $FAIL failed (plutil: $PLUTIL_KIND)"
[ "$FAIL" -eq 0 ]
