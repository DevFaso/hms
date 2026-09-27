#!/usr/bin/env bash
# Asserts the VALUES of the patient iOS app's Info.plist, not merely that the
# keys exist. One script for both gates in .github/workflows/mobile-ios.yml:
#
#   --mode source   after `xcodegen generate`, on every PR. Reads the plist
#                   XcodeGen writes from project.yml, where build-setting
#                   references are still unresolved `$(NAME)` strings.
#   --mode archive  on the .app inside the .xcarchive, before the upload. This
#                   is the plist Apple receives, with Config/*.xcconfig
#                   resolved, so every value is checked as a real value.
#
# The key list is DERIVED from project.yml's `info: properties:` block rather
# than restated here, so a key added there is asserted without touching CI.
#
# Output discipline: a value read from a plist or a config file is never put
# on a `::error::`/`::warning::` line, nor on any line of stdout. The runner
# parses stdout for workflow commands, and these values come from files a PR
# can change. Messages name the KEY and the rule it failed; the offending
# value goes to $GITHUB_STEP_SUMMARY (a file the runner does not scan),
# shell-quoted onto one line, when that variable is set.
#
# Usage:
#   check-info-plist.sh --print-keys --project-yml FILE
#   check-info-plist.sh --mode source  --plist FILE --project-yml FILE \
#                       [--sources DIR] [--xcconfig-dir DIR]
#   check-info-plist.sh --mode archive --plist FILE --project-yml FILE \
#                       --build-number N
#
# Needs plutil (macOS). The tests put a stand-in on PATH to run elsewhere.
# Written for bash 3.2 as well as 5: no associative arrays, no mapfile.
set -euo pipefail

MODE=
PLIST=
PROJECT_YML=
BUILD_NUMBER=
SOURCES_DIR=
XCCONFIG_DIR=
PRINT_KEYS=false

# Regexes live in variables: an unquoted variable on the right of =~ is the
# one spelling bash 3.2 (a stock macOS /bin/bash) and bash 5 read alike.
RE_HTTPS_URL='^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?(/[^[:space:]]*)?$'
# RFC 3986 scheme: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ).
RE_SCHEME='^[A-Za-z][A-Za-z0-9+.-]*$'
RE_SCHEME_PREFIX='^([A-Za-z][A-Za-z0-9+.-]*):'
RE_SETTING_REF='^[$][(][A-Za-z_][A-Za-z0-9_]*[)]$'
RE_DIGITS='^[0-9]+$'
RE_MARKETING='^[0-9]+([.][0-9]+){0,2}$'

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE=${2:-}; shift 2 ;;
    --plist) PLIST=${2:-}; shift 2 ;;
    --project-yml) PROJECT_YML=${2:-}; shift 2 ;;
    --build-number) BUILD_NUMBER=${2:-}; shift 2 ;;
    --sources) SOURCES_DIR=${2:-}; shift 2 ;;
    --xcconfig-dir) XCCONFIG_DIR=${2:-}; shift 2 ;;
    --print-keys) PRINT_KEYS=true; shift ;;
    *) echo "::error::check-info-plist: unknown argument (see the usage at the top of the script)"; exit 2 ;;
  esac
done

FAILURES=0

fail() {
  # $1 is always built from literals and key names, never from a value.
  echo "::error::Info.plist: $1"
  FAILURES=$((FAILURES + 1))
}

warn() {
  echo "::warning::Info.plist: $1"
}

# Record a value where it is safe to show it: the step summary, one
# shell-quoted line. Outside Actions it is dropped rather than printed.
record_value() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    # shellcheck disable=SC2016  # the backticks are Markdown, not a substitution
    printf -- '- %s: `%q`\n' "$1" "$2" >> "$GITHUB_STEP_SUMMARY"
  fi
}

# --------------------------------------------------------------------------
# project.yml parsing
# --------------------------------------------------------------------------

# Prints "KEY<TAB>VALUE" for every direct child of the ONE `info:` block that
# has a `properties:` child. VALUE is the scalar on the key's line (quotes and
# a trailing comment stripped), empty for a block value such as
# CFBundleURLTypes. Fails if there is no such block or more than one: guessing
# which target was meant is how a gate ends up checking nothing.
#
# A line parser for the subset of YAML project.yml uses (block mappings, `#`
# comments; CRLF tolerated), not a YAML implementation. The alternatives on a stock macOS
# runner are Ruby's Psych or a Homebrew yq, and a gate that fails on its own
# tooling is what the old inline check was written to avoid. The tests pin its
# behaviour on a fixture.
info_properties() {
  awk -v q="'" '
    function indent(s) { match(s, /^ */); return RLENGTH }
    function unquote(v,   c) {
      sub(/^[ \t]+/, "", v)
      c = substr(v, 1, 1)
      if (c == "\"" || c == q) {
        v = substr(v, 2)
        return substr(v, 1, index(v, c) - 1)
      }
      sub(/[ \t]+#.*$/, "", v)
      sub(/[ \t]+$/, "", v)
      return v
    }
    { sub(/\r$/, "") }
    /^[ \t]*$/ || /^[ \t]*#/ { next }
    {
      ind = indent($0)
      if (state == 2 && ind <= propIndent) state = 0
      if (state == 1 && ind <= infoIndent) state = 0
      if (state == 0 && $0 ~ /^ *info:[ \t]*(#.*)?$/) {
        state = 1; infoIndent = ind; next
      }
      if (state == 1 && ind > infoIndent && $0 ~ /^ *properties:[ \t]*(#.*)?$/) {
        state = 2; propIndent = ind; childIndent = -1; blocks++; next
      }
      if (state == 2) {
        if (childIndent < 0) childIndent = ind
        if (ind == childIndent && $0 ~ /^ *[A-Za-z_][A-Za-z0-9_.-]*:/) {
          line = $0
          sub(/^ */, "", line)
          key = line; sub(/:.*$/, "", key)
          val = line; sub(/^[^:]*:/, "", val)
          printf "%s\t%s\n", key, unquote(val)
        }
      }
    }
    END {
      if (blocks != 1) exit 3
    }
  ' "$PROJECT_YML"
}

# The default MEDIHUB_KEYCLOAK_REDIRECT_URI from project.yml's settings: the
# literal one, not the `$(...)` reference in the info block.
yml_default_redirect_uri() {
  awk -v q="'" '
    { sub(/\r$/, "") }
    /^[ \t]*MEDIHUB_KEYCLOAK_REDIRECT_URI:/ {
      v = $0; sub(/^[^:]*:[ \t]*/, "", v)
      sub(/[ \t]+#.*$/, "", v)
      c = substr(v, 1, 1)
      if (c == "\"" || c == q) { v = substr(v, 2); v = substr(v, 1, index(v, c) - 1) }
      if (substr(v, 1, 2) != "$(") { print v; exit }
    }
  ' "$PROJECT_YML"
}

# --------------------------------------------------------------------------
# xcconfig resolution (source mode)
# --------------------------------------------------------------------------

# Resolves `NAME = value` from one xcconfig the way Xcode reads that line for
# the cases this project uses: `//` starts a comment ANYWHERE on the line,
# including inside a URL, and `$()` expands to nothing (the escape the Config
# files use to write `https:/$()/host`). Not a full xcconfig evaluator (no
# #include, no conditional settings, no other variable references): the
# archive gate reads what Xcode actually produced and is the authority.
xcconfig_value() {
  awk -v name="$2" '
    {
      line = $0
      sub(/\r$/, "", line)
      c = index(line, "//")
      if (c > 0) line = substr(line, 1, c - 1)
      if (line !~ ("^[ \t]*" name "[ \t]*=")) next
      sub(/^[^=]*=[ \t]*/, "", line)
      while ((i = index(line, "$()")) > 0) line = substr(line, 1, i - 1) substr(line, i + 3)
      sub(/[ \t]+$/, "", line)
      v = line; found = 1
    }
    END { if (found) print v }
  ' "$1"
}

# --------------------------------------------------------------------------
# plist access
# --------------------------------------------------------------------------

pl_xml() { plutil -extract "$1" xml1 -o - "$PLIST" 2>/dev/null; }
pl_raw() { plutil -extract "$1" raw -o - "$PLIST" 2>/dev/null; }
pl_has() { pl_xml "$1" >/dev/null; }

# The element type of a key, read off the root element of the xml1 rendering,
# so a `<string>false</string>` can never pass for a `<false/>`. (`raw`
# prints `false` for both, which is how the first version of the PR-time
# check passed the quoted case.)
pl_type() {
  local body root
  body=$(pl_xml "$1") || { echo missing; return 0; }
  body=$(printf '%s' "$body" | tr -d '[:space:]')
  # Whitespace is gone, so the opening tag reads <plistversion="1.0">.
  root=${body#*<plistversion=\"1.0\">}
  if [ "$root" = "$body" ]; then
    echo other
    return 0
  fi
  case "$root" in
    '<string>'*|'<string/>'*) echo string ;;
    '<true/>'*) echo true ;;
    '<false/>'*) echo false ;;
    '<integer>'*) echo integer ;;
    '<array>'*|'<array/>'*) echo array ;;
    '<dict>'*|'<dict/>'*) echo dict ;;
    *) echo other ;;
  esac
}

# A string value, or empty when the key is missing or is not a string.
pl_string() {
  if [ "$(pl_type "$1")" = string ]; then
    pl_raw "$1"
  fi
}

# Every CFBundleURLSchemes entry across every CFBundleURLTypes item, one per
# line. Only string entries are printed.
url_schemes() {
  local i=0 j
  while pl_has "CFBundleURLTypes.$i"; do
    j=0
    while pl_has "CFBundleURLTypes.$i.CFBundleURLSchemes.$j"; do
      if [ "$(pl_type "CFBundleURLTypes.$i.CFBundleURLSchemes.$j")" = string ]; then
        # Through a substitution so plutil's own trailing newline, present
        # or not, never turns into an empty scheme.
        printf '%s\n' "$(pl_raw "CFBundleURLTypes.$i.CFBundleURLSchemes.$j")"
      fi
      j=$((j + 1))
    done
    i=$((i + 1))
  done
}

is_https_url() {
  [[ $1 =~ $RE_HTTPS_URL ]]
}

scheme_of() {
  if [[ $1 =~ $RE_SCHEME_PREFIX ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

# --------------------------------------------------------------------------
# checks shared by both modes
# --------------------------------------------------------------------------

check_face_id() {
  local v
  if [ "$(pl_type NSFaceIDUsageDescription)" != string ]; then
    fail "NSFaceIDUsageDescription must be a string"
    return 0
  fi
  v=$(pl_raw NSFaceIDUsageDescription)
  if [ -z "$(printf '%s' "$v" | tr -d '[:space:]')" ]; then
    fail "NSFaceIDUsageDescription is empty — iOS terminates an app that reaches Face ID without a purpose string"
  fi
}

# Returns 0 only when the declaration is a real <false/>.
check_export_declaration() {
  case "$(pl_type ITSAppUsesNonExemptEncryption)" in
    false) return 0 ;;
    true)
      if [ "$MODE" = archive ]; then
        fail "ITSAppUsesNonExemptEncryption is true — App Store Connect will not accept that without an ITSEncryptionExportComplianceCode this workflow does not set"
      else
        warn "ITSAppUsesNonExemptEncryption is true — the app now claims non-exempt encryption, and the first-party crypto scan no longer applies"
      fi
      return 1
      ;;
    *)
      # A quoted `false` in project.yml becomes <string>false</string>, which
      # Apple ignores: the build lands in Missing Compliance.
      fail "ITSAppUsesNonExemptEncryption must be a boolean <false/> — a quoted value in project.yml becomes a <string> that Apple ignores"
      return 1
      ;;
  esac
}

check_url_types() {
  local schemes s
  if [ "$(pl_type CFBundleURLTypes)" != array ]; then
    fail "CFBundleURLTypes must be an array — it carries the OAuth redirect scheme every Keycloak sign-in depends on"
    return 0
  fi
  schemes=$(url_schemes)
  if [ -z "$schemes" ]; then
    fail "CFBundleURLTypes declares no CFBundleURLSchemes entry"
    return 0
  fi
  while IFS= read -r s; do
    if ! [[ $s =~ $RE_SCHEME ]]; then
      fail "CFBundleURLTypes carries an entry that is not a valid URL scheme"
      record_value "CFBundleURLSchemes entry" "$s"
    fi
  done <<< "$schemes"
}

# $1 = where the redirect URI came from (a literal label), $2 = the URI.
check_redirect_registered() {
  local origin=$1 uri=$2 scheme schemes
  scheme=$(scheme_of "$uri")
  if [ -z "$scheme" ]; then
    fail "MEDIHUB_KEYCLOAK_REDIRECT_URI from $origin has no URL scheme"
    record_value "MEDIHUB_KEYCLOAK_REDIRECT_URI ($origin)" "$uri"
    return 0
  fi
  # Captured first, then searched: `url_schemes | grep -q` under pipefail
  # can report a MATCH as a failure when grep exits before the producer's
  # last write (SIGPIPE), which would fail a correct plist at random.
  schemes=$(url_schemes)
  if ! grep -qxF -- "$scheme" <<< "$schemes"; then
    fail "the scheme of MEDIHUB_KEYCLOAK_REDIRECT_URI from $origin is not registered in CFBundleURLTypes — the OAuth callback would never reach the app"
    record_value "MEDIHUB_KEYCLOAK_REDIRECT_URI ($origin)" "$uri"
  fi
}

# $1 origin label, $2 SSO flag, $3 issuer. An issuer that does not resolve to
# a URL only matters once SSO is on; until then it is a warning, so a latent
# value is visible without blocking a build that never reads it.
check_issuer() {
  local origin=$1 sso=$2 issuer=$3
  case "$sso" in
    ''|0|1) ;;
    *)
      fail "MEDIHUB_KEYCLOAK_SSO_ENABLED from $origin must be 0 or 1"
      record_value "MEDIHUB_KEYCLOAK_SSO_ENABLED ($origin)" "$sso"
      return 0
      ;;
  esac
  if [ -n "$issuer" ] && ! is_https_url "$issuer"; then
    record_value "MEDIHUB_KEYCLOAK_ISSUER ($origin)" "$issuer"
    if [ "$sso" = 1 ]; then
      fail "MEDIHUB_KEYCLOAK_ISSUER from $origin is not an https URL and SSO is enabled"
    else
      warn "MEDIHUB_KEYCLOAK_ISSUER from $origin is not an https URL (SSO is off, so nothing reads it yet; xcconfig reads // as a comment)"
    fi
  elif [ "$sso" = 1 ] && [ -z "$issuer" ]; then
    fail "SSO is enabled from $origin but MEDIHUB_KEYCLOAK_ISSUER is empty"
  fi
}

# First-party crypto import scan, run when the declaration is false.
scan_first_party_crypto() {
  local found
  [ -n "$SOURCES_DIR" ] || return 0
  if [ ! -d "$SOURCES_DIR" ]; then
    fail "the sources directory to scan is not there, so the crypto scan would have checked nothing"
    return 0
  fi
  # Scans FIRST-PARTY app-target sources for a crypto import. Linked
  # packages are out of scope on purpose: AppAuth's PKCE digest is a
  # standards-track primitive, not this app's algorithm. This catches an
  # import, not every route to cryptography (Security.framework is already
  # imported for the Keychain); metadata.md states that limit.
  #
  # grep's status is captured, not used as a condition: exit 2 (unreadable
  # path, bad regex) must not read as exit 1 (no match).
  set +e
  grep -rnE '(^|[[:space:]])import +(CryptoKit|CommonCrypto|Crypto)([[:space:]]|$)' \
    "$SOURCES_DIR" --include='*.swift' >/dev/null
  found=$?
  set -e
  case "$found" in
    0) fail "ITSAppUsesNonExemptEncryption is false but the app target imports a crypto framework — re-check the export classification before shipping" ;;
    1) echo "export declaration false, and no crypto import in the app target" ;;
    *) fail "the crypto scan could not run (grep exited $found) — not treating that as a pass" ;;
  esac
}

# --------------------------------------------------------------------------
# modes
# --------------------------------------------------------------------------

check_source() {
  local key yml_value want f name issuer sso api redirect
  # Every `$(NAME)` reference in project.yml must reach the generated plist
  # as that exact reference. A literal outranks the build setting: that is
  # how CFBundleVersion stayed "1" while CI set CURRENT_PROJECT_VERSION, and
  # every upload after the first was a duplicate.
  while IFS=$'\t' read -r key yml_value; do
    if [[ $yml_value =~ $RE_SETTING_REF ]] && [ "$(pl_string "$key")" != "$yml_value" ]; then
      fail "$key must be the build-setting reference project.yml declares, not a literal"
      record_value "$key" "$(pl_string "$key")"
    fi
  done <<< "$KEYS"

  # Pinned whatever project.yml says: these two must follow the build
  # settings, or the build number CI computes is inert.
  for key in CFBundleVersion CFBundleShortVersionString; do
    # shellcheck disable=SC2016  # literal build-setting references, not substitutions
    case "$key" in
      CFBundleVersion) want='$(CURRENT_PROJECT_VERSION)' ;;
      *) want='$(MARKETING_VERSION)' ;;
    esac
    if [ "$(pl_string "$key")" != "$want" ]; then
      fail "$key must be the build-setting reference, never a literal"
      record_value "$key" "$(pl_string "$key")"
    fi
  done

  check_face_id
  check_url_types
  if check_export_declaration; then
    scan_first_party_crypto
  fi

  redirect=$(yml_default_redirect_uri)
  if [ -n "$redirect" ]; then
    check_redirect_registered "project.yml settings" "$redirect"
  fi

  # The Release-* configurations are only resolved at archive time, which a
  # PR never reaches. Resolve each Config/*.xcconfig here so a bad per-env
  # value fails the PR rather than the release.
  if [ -n "$XCCONFIG_DIR" ]; then
    for f in "$XCCONFIG_DIR"/*.xcconfig; do
      [ -f "$f" ] || continue
      name=$(basename "$f")
      api=$(xcconfig_value "$f" MEDIHUB_API_BASE_URL)
      if ! is_https_url "$api"; then
        fail "MEDIHUB_API_BASE_URL in $name does not resolve to an https URL (xcconfig reads // as a comment: write https:/\$()/host)"
        record_value "MEDIHUB_API_BASE_URL ($name)" "$api"
      fi
      redirect=$(xcconfig_value "$f" MEDIHUB_KEYCLOAK_REDIRECT_URI)
      if [ -n "$redirect" ]; then
        check_redirect_registered "$name" "$redirect"
      fi
      sso=$(xcconfig_value "$f" MEDIHUB_KEYCLOAK_SSO_ENABLED)
      issuer=$(xcconfig_value "$f" MEDIHUB_KEYCLOAK_ISSUER)
      check_issuer "$name" "$sso" "$issuer"
    done
  fi
}

check_archive() {
  local key v short api sso issuer redirect
  if ! [[ $BUILD_NUMBER =~ $RE_DIGITS ]]; then
    echo "::error::check-info-plist: --build-number must be the numeric build number the archive was built with"
    exit 2
  fi

  # Nothing the archive carries may still be an unresolved reference: that
  # is a build setting never defined for this configuration.
  while IFS=$'\t' read -r key _; do
    v=$(pl_string "$key")
    # shellcheck disable=SC2016  # matching a literal "$(" in the value
    case "$v" in
      *'$('*)
        fail "$key reached the archive as an unresolved build-setting reference"
        record_value "$key" "$v"
        ;;
    esac
  done <<< "$KEYS"

  v=$(pl_string CFBundleVersion)
  if [ "$v" != "$BUILD_NUMBER" ]; then
    fail "CFBundleVersion is not the build number this run computed — App Store Connect would reject the upload as a duplicate build"
    record_value "CFBundleVersion" "$v"
  fi

  short=$(pl_string CFBundleShortVersionString)
  if ! [[ $short =~ $RE_MARKETING ]]; then
    fail "CFBundleShortVersionString is not a dotted numeric marketing version"
    record_value "CFBundleShortVersionString" "$short"
  fi

  api=$(pl_string MEDIHUB_API_BASE_URL)
  if ! is_https_url "$api"; then
    fail "MEDIHUB_API_BASE_URL in the archive is not a non-empty https URL — the app would reach no server"
    record_value "MEDIHUB_API_BASE_URL" "$api"
  fi

  sso=$(pl_string MEDIHUB_KEYCLOAK_SSO_ENABLED)
  issuer=$(pl_string MEDIHUB_KEYCLOAK_ISSUER)
  check_issuer "the archive" "$sso" "$issuer"

  check_face_id
  check_url_types
  check_export_declaration || true

  redirect=$(pl_string MEDIHUB_KEYCLOAK_REDIRECT_URI)
  if [ -n "$redirect" ]; then
    check_redirect_registered "the archive" "$redirect"
  elif [ "$sso" = 1 ]; then
    fail "SSO is enabled but MEDIHUB_KEYCLOAK_REDIRECT_URI is empty in the archive"
  fi
}

# --------------------------------------------------------------------------

if [ -z "$PROJECT_YML" ] || [ ! -f "$PROJECT_YML" ]; then
  echo "::error::check-info-plist: --project-yml must name the project.yml to derive the key list from"
  exit 2
fi

if ! KEYS=$(info_properties); then
  echo "::error::check-info-plist: could not derive the key list from project.yml (expected exactly one info: properties: block)"
  exit 2
fi
if [ -z "$KEYS" ]; then
  echo "::error::check-info-plist: project.yml's info: properties: block declares no keys"
  exit 2
fi

if [ "$PRINT_KEYS" = true ]; then
  printf '%s\n' "$KEYS" | cut -f1
  exit 0
fi

case "$MODE" in
  source|archive) ;;
  *) echo "::error::check-info-plist: --mode must be source or archive"; exit 2 ;;
esac

if [ -z "$PLIST" ] || [ ! -f "$PLIST" ]; then
  echo "::error::check-info-plist: the plist to check is not there"
  exit 1
fi

# A malformed plist is reported as that, not as a list of missing keys that
# sends the reader hunting in project.yml for something that is right there.
# plutil's diagnostic names the file and a line; `::` is defused regardless.
if ! lint=$(plutil -lint "$PLIST" 2>&1); then
  echo "::error::Info.plist: plutil cannot parse the plist"
  printf '%s\n' "$lint" | sed 's/::/: :/g'
  exit 1
fi

# Presence first, for every key project.yml declares.
while IFS=$'\t' read -r key _; do
  if ! pl_has "$key"; then
    fail "$key is declared in project.yml but missing from the plist"
  fi
done <<< "$KEYS"

if [ "$MODE" = source ]; then
  check_source
else
  check_archive
fi

count=$(printf '%s\n' "$KEYS" | wc -l | tr -d ' ')
if [ "$FAILURES" -gt 0 ]; then
  echo "Info.plist ($MODE): $FAILURES check(s) failed across $count declared key(s)"
  exit 1
fi
echo "Info.plist ($MODE): all $count declared key(s) present and their values valid"
