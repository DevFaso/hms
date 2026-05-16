#!/usr/bin/env bash
#
# scripts/keycloak/env-sync-verify.sh
#
# Companion to docs/runbooks/keycloak-env-sync-audit-2026-05-12.md and
# docs/runbooks/keycloak-realm-sync.md.
#
# Verifies that the Keycloak deployment in dev/uat/prod agrees with what
# this repo describes. Designed to be runnable from anywhere — not from
# inside the Railway container — and to fail loudly when drift appears.
#
# Two modes:
#   --public-only (default) — no credentials required. Checks per env:
#     P1. OIDC discovery endpoint returns 200 + valid JSON
#     P2. Discovery's "issuer" field matches the configured KC hostname
#     P3. Discovery exposes authorization/token/jwks endpoints over HTTPS
#     P4. Frontend env file's oidc.issuer matches discovery's issuer
#     Plus repo-wide:
#     R1. Branch divergence (develop vs uat vs main)
#     R2. realm-export.json hms-portal redirect URIs cover all four host envs
#     R3. realm-export.json + redirect-uris.md agree
#     R4. Frontend env files point at distinct env hostnames
#
#   --full — additionally prompts for an admin password per env and runs:
#     A1. Live realm's hms-portal redirect URIs match realm-export.json
#     A2. Live realm role count matches realm-export.json (currently 26)
#     A3. dev.* user policy per keycloak/README.md § Policy:
#           hosted dev  — dev.admin FORBIDDEN, dev.doctor/dev.patient OK
#           hosted uat  — all three FORBIDDEN
#           hosted prod — all three FORBIDDEN
#
# Exit codes:
#   0 — all checks passed
#   1 — at least one check failed (printed to stderr with detail)
#   2 — invocation error (missing tool, bad flag, missing repo file)
#
# Required tools: curl, jq, git, python3 (for --full URL-encode of password
# via stdin — same shape as keycloak-realm-sync.md uses curl --data-urlencode
# password@-, included here as a portable fallback).
#
# Usage:
#   scripts/keycloak/env-sync-verify.sh                 # public-only
#   scripts/keycloak/env-sync-verify.sh --full          # also live-realm diffs
#   scripts/keycloak/env-sync-verify.sh --env uat       # restrict to one env
#   scripts/keycloak/env-sync-verify.sh --json          # machine-readable output
#
set -euo pipefail

# ─── Args ────────────────────────────────────────────────────────────────────
MODE="public-only"
ENV_FILTER=""
JSON_OUTPUT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --full)        MODE="full" ;;
    --public-only) MODE="public-only" ;;
    --env)
      # Defensive: under set -u, a bare `shift; ENV_FILTER="$1"` would crash
      # with "unbound variable" if --env is the final arg or followed by
      # another flag. Validate up front and emit a helpful exit-2 message.
      if [[ $# -lt 2 || "$2" == --* ]]; then
        echo "ERROR: --env requires a value (one of: dev | uat | prod)" >&2
        exit 2
      fi
      shift
      case "$1" in
        dev|uat|prod) ENV_FILTER="$1" ;;
        *) echo "ERROR: --env must be one of dev|uat|prod (got: $1)" >&2; exit 2 ;;
      esac
      ;;
    --json)        JSON_OUTPUT=true ;;
    -h|--help)
      sed -n '2,/^set -euo/p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
  shift
done

# ─── Pre-flight ──────────────────────────────────────────────────────────────
for tool in curl jq git; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: missing tool '$tool'" >&2
    case "$tool" in
      jq)
        echo "  Install hint:" >&2
        echo "    Windows (Git Bash): scoop install jq    (or winget install jqlang.jq)" >&2
        echo "    macOS:              brew install jq" >&2
        echo "    Linux (apt):        sudo apt install jq" >&2
        ;;
      curl)
        echo "  curl ships with Git for Windows / macOS / most Linux distros — check PATH" >&2
        ;;
    esac
    exit 2
  fi
done

# Wrap jq so its output never carries CR. On Git Bash / MSYS, jq opens stdout in
# text mode, which translates LF → CRLF. That CR leaks into `read -r` (kept on
# the line), into `$()` captures used in arithmetic `[[ -gt ]]` context, and
# into HTTP headers (a token with trailing CR makes `Authorization: Bearer …`
# malformed and A1/A2/A3 silently fail).
jq() { command jq "$@" | tr -d '\r'; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[[ -z "$REPO_ROOT" ]] && { echo "ERROR: not inside a git repo" >&2; exit 2; }
cd "$REPO_ROOT"

REALM_EXPORT="keycloak/realm-export.json"
[[ -f "$REALM_EXPORT" ]] || { echo "ERROR: $REALM_EXPORT not found at repo root" >&2; exit 2; }

# ─── Per-env contract (mirrors docs/runbooks/railway-env-matrix.md) ──────────
# Service host, frontend env file, expected discovery issuer.
declare -a ENVS
if [[ -n "$ENV_FILTER" ]]; then
  ENVS=("$ENV_FILTER")
else
  ENVS=(dev uat prod)
fi

kc_host_for() {
  case "$1" in
    dev)  echo "https://hms-keycloak-dev-dev.up.railway.app" ;;
    uat)  echo "https://hms-keycloak-uat-uat.up.railway.app" ;;
    prod) echo "https://hms-keycloak-prod-prod.up.railway.app" ;;
    *)    echo "ERROR: unknown env '$1'" >&2; return 2 ;;
  esac
}

frontend_env_file_for() {
  case "$1" in
    dev)  echo "hospital-portal/src/environments/environment.dev.ts" ;;
    uat)  echo "hospital-portal/src/environments/environment.uat.ts" ;;
    prod) echo "hospital-portal/src/environments/environment.prod.ts" ;;
  esac
}

# ─── Result accumulation ─────────────────────────────────────────────────────
# Each result row is:  STATUS|SCOPE|CHECK_ID|MESSAGE
# STATUS is PASS, FAIL, or SKIP.
RESULTS=()
record() {
  RESULTS+=("$1|$2|$3|$4")
}
fail_count=0
pass_count=0
skip_count=0

# ─── Check helpers ───────────────────────────────────────────────────────────

# Checks 200 + JSON parse from a URL. Echoes the parsed JSON on stdout if OK.
fetch_json() {
  local url="$1" body
  body=$(curl -fsSL --max-time 10 "$url" 2>/dev/null) || return 1
  printf '%s' "$body" | jq -e . >/dev/null 2>&1 || return 1
  printf '%s' "$body"
}

# Reads `oidc.issuer` from a TypeScript environment.<env>.ts file.
# Doesn't run TS — uses a regex grep, which is enough for the
# fixed shape `issuer: '<url>'` we ship.
read_frontend_issuer() {
  local file="$1"
  grep -E "issuer:\\s*'[^']+'" "$file" 2>/dev/null \
    | head -n 1 \
    | sed -E "s/.*issuer:\\s*'([^']+)'.*/\\1/"
}

# ─── Public-only checks ──────────────────────────────────────────────────────

run_public_env_checks() {
  local env="$1" host issuer_expected discovery issuer_actual
  host="$(kc_host_for "$env")"
  issuer_expected="${host}/realms/hms"

  # P1. OIDC discovery
  if discovery=$(fetch_json "${host}/realms/hms/.well-known/openid-configuration"); then
    record PASS "$env" P1 "OIDC discovery returned 200 + valid JSON"
    pass_count=$((pass_count + 1))
  else
    record FAIL "$env" P1 "OIDC discovery unreachable or returned invalid JSON at ${host}/realms/hms/.well-known/openid-configuration"
    fail_count=$((fail_count + 1))
    # Subsequent P checks for this env need the discovery doc — skip them.
    record SKIP "$env" P2 "skipped: depends on P1"
    record SKIP "$env" P3 "skipped: depends on P1"
    skip_count=$((skip_count + 2))
    return
  fi

  # P2. issuer claim matches expected hostname
  issuer_actual=$(printf '%s' "$discovery" | jq -r .issuer)
  if [[ "$issuer_actual" == "$issuer_expected" ]]; then
    record PASS "$env" P2 "discovery issuer = ${issuer_actual}"
    pass_count=$((pass_count + 1))
  else
    record FAIL "$env" P2 "discovery issuer mismatch — expected ${issuer_expected}, got ${issuer_actual}"
    fail_count=$((fail_count + 1))
  fi

  # P3. all OAuth endpoints are HTTPS
  local non_https
  non_https=$(printf '%s' "$discovery" \
    | jq -r '[.authorization_endpoint, .token_endpoint, .jwks_uri, .end_session_endpoint] | .[] | select(. != null and (startswith("https://") | not))')
  if [[ -z "$non_https" ]]; then
    record PASS "$env" P3 "auth/token/jwks/end_session endpoints are all HTTPS"
    pass_count=$((pass_count + 1))
  else
    record FAIL "$env" P3 "non-HTTPS endpoints in discovery: ${non_https//$'\n'/, }"
    fail_count=$((fail_count + 1))
  fi

  # P4. Frontend env file agrees with discovery issuer
  local fe_file fe_issuer
  fe_file="$(frontend_env_file_for "$env")"
  if [[ ! -f "$fe_file" ]]; then
    record SKIP "$env" P4 "skipped: ${fe_file} not found"
    skip_count=$((skip_count + 1))
    return
  fi
  fe_issuer="$(read_frontend_issuer "$fe_file")"
  if [[ "$fe_issuer" == "$issuer_actual" ]]; then
    record PASS "$env" P4 "frontend ${fe_file} agrees with discovery (${fe_issuer})"
    pass_count=$((pass_count + 1))
  else
    record FAIL "$env" P4 "frontend ${fe_file} carries '${fe_issuer}', discovery returned '${issuer_actual}'"
    fail_count=$((fail_count + 1))
  fi
}

run_repo_checks() {
  # R1. Branch divergence — informational; surfaces if any pair has diverged
  # in keycloak/ or scripts/keycloak/ paths.
  local d_uat d_main uat_main
  if git fetch -q origin develop uat main 2>/dev/null; then
    d_uat=$(git rev-list --left-right --count origin/develop...origin/uat 2>/dev/null || echo "?/?")
    d_main=$(git rev-list --left-right --count origin/develop...origin/main 2>/dev/null || echo "?/?")
    uat_main=$(git rev-list --left-right --count origin/uat...origin/main 2>/dev/null || echo "?/?")
    record PASS repo R1 "branch divergence: develop↔uat=${d_uat//	/ / }, develop↔main=${d_main//	/ / }, uat↔main=${uat_main//	/ / }"
    pass_count=$((pass_count + 1))
  else
    record SKIP repo R1 "skipped: git fetch failed (offline?)"
    skip_count=$((skip_count + 1))
  fi

  # R2. realm-export.json's hms-portal carries all four host envs
  local missing_uris
  missing_uris=$(jq -r '
    .clients[] | select(.clientId=="hms-portal") | .redirectUris | sort |
    (["http://localhost:4200/*",
      "https://hms.dev.bitnesttechs.com/*",
      "https://hms.uat.bitnesttechs.com/*",
      "https://hms.bitnesttechs.com/*"] - .) | .[]
  ' "$REALM_EXPORT")
  if [[ -z "$missing_uris" ]]; then
    record PASS repo R2 "hms-portal redirectUris cover localhost + dev + uat + prod hosts"
    pass_count=$((pass_count + 1))
  else
    record FAIL repo R2 "hms-portal redirectUris missing: ${missing_uris//$'\n'/, }"
    fail_count=$((fail_count + 1))
  fi

  # R3. redirect-uris.md table mentions every URI in realm-export
  local rmd="keycloak/redirect-uris.md" missing_in_md
  if [[ ! -f "$rmd" ]]; then
    record SKIP repo R3 "skipped: ${rmd} not found"
    skip_count=$((skip_count + 1))
  else
    missing_in_md=""
    while IFS= read -r uri; do
      [[ -z "$uri" ]] && continue
      grep -qF "$uri" "$rmd" || missing_in_md+="${uri} "
    done < <(jq -r '.clients[] | select(.clientId=="hms-portal") | .redirectUris[]' "$REALM_EXPORT")
    if [[ -z "$missing_in_md" ]]; then
      record PASS repo R3 "${rmd} mentions every hms-portal redirect URI"
      pass_count=$((pass_count + 1))
    else
      record FAIL repo R3 "${rmd} is missing: ${missing_in_md}"
      fail_count=$((fail_count + 1))
    fi
  fi

  # R4. Frontend env files point at distinct hosts (catch a paste-bug where
  # env.dev.ts accidentally points at uat or prod).
  local seen=""
  local dup=false
  for env in "${ENVS[@]}"; do
    local fe_file issuer
    fe_file="$(frontend_env_file_for "$env")"
    [[ -f "$fe_file" ]] || continue
    issuer="$(read_frontend_issuer "$fe_file")"
    [[ -z "$issuer" ]] && continue
    if printf '%s' "$seen" | grep -qFx "$issuer"; then
      dup=true
      record FAIL repo R4 "duplicate frontend issuer across env files: ${issuer}"
      fail_count=$((fail_count + 1))
      break
    fi
    seen+="${issuer}"$'\n'
  done
  if [[ "$dup" == "false" ]]; then
    record PASS repo R4 "frontend env files point at distinct issuers"
    pass_count=$((pass_count + 1))
  fi
}

# ─── Authenticated (--full) checks ───────────────────────────────────────────

prompt_admin_token() {
  # Prints the token on stdout. Exit codes:
  #   0 — success, token on stdout
  #   1 — user skipped (empty username at prompt)
  #   2 — login attempted but failed (wrong creds, user not in master realm, etc.)
  #
  # Common rc=2 cause on locked-down envs: admin has TOTP/MFA enrolled.
  # Keycloak's direct-grant flow returns `invalid_grant` for MFA users —
  # indistinguishable from bad-password in the error body. For automation
  # (this script, KC-4 migration) create a short-lived migration-admin in
  # the master realm without OTP and retire it after the run.
  local env="$1" host="$2" admin_user admin_pass token
  read -rp "[${env}] admin username (Enter to skip --full for this env): " admin_user
  [[ -z "$admin_user" ]] && return 1
  read -rsp "[${env}] password for ${admin_user}: " admin_pass; echo

  token=$(printf '%s' "$admin_pass" | curl -fsSL --max-time 10 -X POST \
    "${host}/realms/master/protocol/openid-connect/token" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=${admin_user}" \
    --data-urlencode "password@-" 2>/dev/null \
    | jq -r '.access_token // empty')
  unset admin_pass
  [[ -z "$token" || "$token" == "null" ]] && return 2
  printf '%s' "$token"
}

run_authenticated_env_checks() {
  local env="$1" host token live_uris export_uris auth_rc reason
  host="$(kc_host_for "$env")"

  token=$(prompt_admin_token "$env" "$host"); auth_rc=$?
  if [[ $auth_rc -eq 1 ]]; then
    reason="skipped: no admin credentials provided for ${env}"
    record SKIP "$env" A1 "$reason"
    record SKIP "$env" A2 "$reason"
    record SKIP "$env" A3 "$reason"
    skip_count=$((skip_count + 3))
    return
  elif [[ $auth_rc -eq 2 ]]; then
    record FAIL "$env" A1 "login to ${host}/realms/master failed for the username you provided — wrong password, user not in master realm, admin-cli access denied, OR admin has MFA enrolled (direct-grant rejects TOTP users; create a no-OTP migration-admin for automation)"
    record SKIP "$env" A2 "skipped: depends on A1 admin login"
    record SKIP "$env" A3 "skipped: depends on A1 admin login"
    fail_count=$((fail_count + 1))
    skip_count=$((skip_count + 2))
    return
  fi

  # Helper: GET a Keycloak admin endpoint and return "<http_status>|<body>" so
  # callers can distinguish HTTP-level failures (auth/perms) from data drift.
  # Uses -sS instead of -fsSL so the body is returned even on 4xx/5xx.
  local _kc_resp _kc_status _kc_body
  kc_admin_get() {
    _kc_resp=$(curl -sS --max-time 10 -w $'\n__HTTP__%{http_code}' \
      -H "Authorization: Bearer $token" "$1" 2>&1) || _kc_resp="${_kc_resp}"$'\n__HTTP__000'
    _kc_status="${_kc_resp##*__HTTP__}"
    _kc_body="${_kc_resp%$'\n'__HTTP__*}"
  }

  # A1. live hms-portal redirectUris match realm-export
  kc_admin_get "${host}/admin/realms/hms/clients?clientId=hms-portal"
  if [[ "$_kc_status" != "200" ]]; then
    record FAIL "$env" A1 "HTTP ${_kc_status} from /admin/realms/hms/clients — admin token lacks realm-management on the hms realm (or realm/path wrong). Body: $(printf '%s' "$_kc_body" | head -c 200)"
    fail_count=$((fail_count + 1))
  else
    live_uris=$(printf '%s' "$_kc_body" | jq -S '.[0].redirectUris | sort' 2>/dev/null || true)
    export_uris=$(jq -S '.clients[] | select(.clientId=="hms-portal") | .redirectUris | sort' "$REALM_EXPORT")
    if [[ "$live_uris" == "$export_uris" ]]; then
      record PASS "$env" A1 "live hms-portal redirectUris match realm-export.json"
      pass_count=$((pass_count + 1))
    else
      record FAIL "$env" A1 "live hms-portal redirectUris differ from realm-export.json — partial-import per keycloak-realm-sync.md. Live: $(printf '%s' "$live_uris" | tr -d '\n ' | head -c 200)"
      fail_count=$((fail_count + 1))
    fi
  fi

  # A2. live realm role count == export role count
  local live_role_count export_role_count
  kc_admin_get "${host}/admin/realms/hms/roles"
  export_role_count=$(jq '.roles.realm | length' "$REALM_EXPORT")
  if [[ "$_kc_status" != "200" ]]; then
    record FAIL "$env" A2 "HTTP ${_kc_status} from /admin/realms/hms/roles — same likely cause as A1. Body: $(printf '%s' "$_kc_body" | head -c 200)"
    fail_count=$((fail_count + 1))
  else
    live_role_count=$(printf '%s' "$_kc_body" | jq 'length' 2>/dev/null || echo "?")
    if [[ "$live_role_count" == "$export_role_count" ]]; then
      record PASS "$env" A2 "realm role count matches export (${live_role_count})"
      pass_count=$((pass_count + 1))
    else
      record FAIL "$env" A2 "realm role count drift — live=${live_role_count}, export=${export_role_count}"
      fail_count=$((fail_count + 1))
    fi
  fi

  # A3. dev.* user policy (per keycloak/README.md § "Policy — which dev
  # users are OK on which environment"):
  #   hosted dev  — dev.doctor + dev.patient ALLOWED, dev.admin FORBIDDEN
  #   hosted uat  — all three FORBIDDEN
  #   hosted prod — all three FORBIDDEN
  local present=""
  for u in dev.admin dev.doctor dev.patient; do
    local hits
    hits=$(curl -fsSL --max-time 10 -H "Authorization: Bearer $token" \
      "${host}/admin/realms/hms/users?username=${u}&exact=true" 2>/dev/null \
      | jq 'length' 2>/dev/null || echo "0")
    [[ "$hits" -gt 0 ]] && present+="${u} "
  done

  local violations=""
  case "$env" in
    dev)
      # dev.admin is the only forbidden one on hosted dev.
      [[ "$present" == *"dev.admin"* ]] && violations="dev.admin"
      ;;
    uat|prod)
      # All three are forbidden.
      violations="$present"
      ;;
  esac

  if [[ -z "$violations" ]]; then
    if [[ -n "$present" ]]; then
      record PASS "$env" A3 "policy-allowed dev users present in ${env}: ${present}"
    else
      record PASS "$env" A3 "no dev.* users in ${env} realm"
    fi
    pass_count=$((pass_count + 1))
  else
    record FAIL "$env" A3 "POLICY violation in ${env} — forbidden dev user(s) present: ${violations} — delete via admin console (see keycloak/README.md § Policy)"
    fail_count=$((fail_count + 1))
  fi

  unset token
}

# ─── Run ─────────────────────────────────────────────────────────────────────

run_repo_checks
for env in "${ENVS[@]}"; do
  run_public_env_checks "$env"
  [[ "$MODE" == "full" ]] && run_authenticated_env_checks "$env"
done

# ─── Output ──────────────────────────────────────────────────────────────────

if [[ "$JSON_OUTPUT" == "true" ]]; then
  printf '['
  local first=true
  for row in "${RESULTS[@]}"; do
    IFS='|' read -r status scope check msg <<< "$row"
    [[ "$first" == "true" ]] && first=false || printf ','
    jq -nc --arg s "$status" --arg sc "$scope" --arg c "$check" --arg m "$msg" \
      '{status:$s, scope:$sc, check:$c, message:$m}'
  done
  printf ']\n'
else
  printf '\n%-6s %-6s %-4s %s\n' STATUS SCOPE CHK MESSAGE
  printf '%-6s %-6s %-4s %s\n' '------' '------' '----' '----------------------------------------------------'
  for row in "${RESULTS[@]}"; do
    IFS='|' read -r status scope check msg <<< "$row"
    printf '%-6s %-6s %-4s %s\n' "$status" "$scope" "$check" "$msg"
  done
  printf '\nTotals: %d passed, %d failed, %d skipped\n' "$pass_count" "$fail_count" "$skip_count"
fi

if [[ "$fail_count" -eq 0 ]]; then
  [[ "$JSON_OUTPUT" == "true" ]] || echo "[env-sync-verify] OK"
  exit 0
else
  [[ "$JSON_OUTPUT" == "true" ]] || echo "[env-sync-verify] FAIL — see rows marked FAIL above" >&2
  exit 1
fi
