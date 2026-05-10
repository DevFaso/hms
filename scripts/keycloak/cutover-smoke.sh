#!/usr/bin/env bash
#
# scripts/keycloak/cutover-smoke.sh
#
# Roadmap row 8 / KC-5 — packaged smoke check the on-call runs immediately
# after flipping app.auth.oidc.required=true. Replaces the inline curl block
# in docs/runbooks/keycloak-cutover-runbook.md §3 so the same exact bytes
# can run from a laptop, a CI job, or a cron monitor.
#
# Verifies, in order:
#   1. POST /auth/login          → 410 Gone, runbook copy in body
#   2. POST /auth/login          → Link: rel="oauth2-issuer" header present
#                                  pointing at <issuer>/.well-known/openid-configuration
#   3. POST /auth/token/refresh  → 410 Gone, runbook copy in body
#   4. GET  /auth/ping           → 200 (connectivity sanity)
#
# Exit codes:
#   0 — all four checks passed
#   1 — any check failed (printed to stderr with diff)
#   2 — invocation error (missing curl/jq, missing required env)
#
# Required env:
#   API_BASE_URL   e.g. https://api.hms.uat.bitnesttechs.com (no trailing slash)
#
# Optional env:
#   ISSUER_URI     e.g. https://keycloak.uat.example.com/realms/hms
#                  When set, the script asserts the Link header carries the
#                  matching discovery URL. When unset, the Link assertion is
#                  relaxed to "header is either absent or non-empty".
#   SMOKE_USER     username to attempt login with (default: smoketest)
#                  The user does not need to exist — the flag short-circuits
#                  before authentication.
#
# Usage:
#   API_BASE_URL=https://api.hms.uat.bitnesttechs.com \
#   ISSUER_URI=https://keycloak.uat.example.com/realms/hms \
#     scripts/keycloak/cutover-smoke.sh
#
set -euo pipefail

# ─── Pre-flight ────────────────────────────────────────────────────────────
for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required tool '$tool' is not installed" >&2
    exit 2
  fi
done

if [[ -z "${API_BASE_URL:-}" ]]; then
  echo "ERROR: API_BASE_URL must be set (e.g. https://api.hms.uat.bitnesttechs.com)" >&2
  exit 2
fi
API_BASE_URL="${API_BASE_URL%/}"
SMOKE_USER="${SMOKE_USER:-smoketest}"

EXPECTED_LOGIN_MSG="Legacy username/password login is disabled. Sign in via Single Sign-On."
EXPECTED_REFRESH_MSG="Legacy token refresh is disabled. Sign in via Single Sign-On."

# Track failures so we can report all of them at the end rather than bailing
# on the first one — operators want one round-trip, not four console hops.
fail_count=0
log_fail() { echo "  ✗ $*" >&2; fail_count=$((fail_count + 1)); }
log_ok()   { echo "  ✓ $*"; }

# ─── 1. Legacy login → 410 with runbook copy ───────────────────────────────
echo "[smoke] POST $API_BASE_URL/auth/login"
login_tmp="$(mktemp)"
trap 'rm -f "$login_tmp"' EXIT
login_status=$(curl -sS -o "$login_tmp" -w '%{http_code}' \
  -D "${login_tmp}.headers" \
  -X POST "$API_BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SMOKE_USER\",\"password\":\"whatever\"}")

if [[ "$login_status" == "410" ]]; then
  log_ok "status 410"
else
  log_fail "expected 410, got $login_status (body: $(head -c 200 "$login_tmp"))"
fi

login_msg=$(jq -r '.message // empty' "$login_tmp" 2>/dev/null || true)
if [[ "$login_msg" == "$EXPECTED_LOGIN_MSG" ]]; then
  log_ok "message text matches runbook copy"
else
  log_fail "message mismatch — expected: $EXPECTED_LOGIN_MSG ; got: $login_msg"
fi

# ─── 2. Link: rel="oauth2-issuer" header sanity ────────────────────────────
link_header=$(awk -F': ' 'tolower($1) == "link" { sub(/\r$/, "", $2); print $2 }' \
  "${login_tmp}.headers" 2>/dev/null || true)

if [[ -n "${ISSUER_URI:-}" ]]; then
  expected_discovery="${ISSUER_URI%/}/.well-known/openid-configuration"
  if [[ "$link_header" == *"<${expected_discovery}>"* && "$link_header" == *'rel="oauth2-issuer"'* ]]; then
    log_ok "Link header carries oauth2-issuer pointing at $expected_discovery"
  else
    log_fail "Link header missing or wrong — expected oauth2-issuer rel for $expected_discovery ; got: $link_header"
  fi
else
  if [[ -z "$link_header" ]]; then
    log_ok "Link header absent (ISSUER_URI not set — local-dev shape)"
  elif [[ "$link_header" == *'rel="oauth2-issuer"'* ]]; then
    log_ok "Link header present (skipped exact match — ISSUER_URI not provided)"
  else
    log_fail "Link header present but unexpected shape: $link_header"
  fi
fi

# ─── 3. Legacy refresh → 410 with runbook copy ─────────────────────────────
echo "[smoke] POST $API_BASE_URL/auth/token/refresh"
refresh_tmp="$(mktemp)"
trap 'rm -f "$login_tmp" "${login_tmp}.headers" "$refresh_tmp"' EXIT
refresh_status=$(curl -sS -o "$refresh_tmp" -w '%{http_code}' \
  -X POST "$API_BASE_URL/auth/token/refresh" \
  -H 'Content-Type: application/json' \
  -d '{}')

if [[ "$refresh_status" == "410" ]]; then
  log_ok "status 410"
else
  log_fail "expected 410, got $refresh_status (body: $(head -c 200 "$refresh_tmp"))"
fi

refresh_msg=$(jq -r '.message // empty' "$refresh_tmp" 2>/dev/null || true)
if [[ "$refresh_msg" == "$EXPECTED_REFRESH_MSG" ]]; then
  log_ok "message text matches runbook copy"
else
  log_fail "message mismatch — expected: $EXPECTED_REFRESH_MSG ; got: $refresh_msg"
fi

# ─── 4. Connectivity sanity ────────────────────────────────────────────────
echo "[smoke] GET  $API_BASE_URL/auth/ping"
ping_status=$(curl -sS -o /dev/null -w '%{http_code}' "$API_BASE_URL/auth/ping")
if [[ "$ping_status" == "200" ]]; then
  log_ok "ping 200"
else
  log_fail "expected 200, got $ping_status (backend may be unhealthy or behind a 401 wall)"
fi

# ─── Summary ───────────────────────────────────────────────────────────────
if [[ $fail_count -eq 0 ]]; then
  echo "[smoke] OK — Phase C cutover invariants hold against $API_BASE_URL"
  exit 0
else
  echo "[smoke] FAIL — $fail_count check(s) failed against $API_BASE_URL" >&2
  echo "        See docs/runbooks/keycloak-cutover-runbook.md §Rollback before re-trying." >&2
  exit 1
fi
