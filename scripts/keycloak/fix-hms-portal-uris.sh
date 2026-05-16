#!/usr/bin/env bash
#
# scripts/keycloak/fix-hms-portal-uris.sh
#
# Remediation script for the A1 drift surfaced by env-sync-verify.sh:
# live hms-portal client carries redirect/web-origin/post-logout URIs that
# differ from keycloak/realm-export.json. Brings the live client into line
# with the export, touching ONLY:
#   - redirectUris
#   - webOrigins
#   - attributes."post.logout.redirect.uris"
#
# Everything else on the client (UUID, secret, flows, scopes, attributes
# other than post.logout.redirect.uris) is preserved by round-tripping the
# live client representation through jq.
#
# Usage:
#   scripts/keycloak/fix-hms-portal-uris.sh --env uat              # apply
#   scripts/keycloak/fix-hms-portal-uris.sh --env uat --dry-run    # diff only
#
# Idempotent: a second run after success is a no-op (script detects the
# live config already matches the export and exits 0 without a PUT).
#
# Prompts for admin username + password interactively — credentials never
# go on the command line and never leave stdin.
#
set -euo pipefail

ENV_FILTER=""
DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      if [[ $# -lt 2 || "$2" == --* ]]; then
        echo "ERROR: --env requires a value (one of: dev | uat | prod)" >&2
        exit 2
      fi
      shift
      case "$1" in
        dev|uat|prod) ENV_FILTER="$1" ;;
        *) echo "ERROR: --env must be one of dev|uat|prod" >&2; exit 2 ;;
      esac
      ;;
    --dry-run) DRY_RUN=true ;;
    -h|--help)
      sed -n '2,/^set -euo/p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
  shift
done

[[ -z "$ENV_FILTER" ]] && { echo "ERROR: --env is required" >&2; exit 2; }

for tool in curl jq git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: missing tool '$tool'" >&2; exit 2; }
done

# Same Windows-CR fix as env-sync-verify.sh.
jq() { command jq "$@" | tr -d '\r'; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[[ -z "$REPO_ROOT" ]] && { echo "ERROR: not inside a git repo" >&2; exit 2; }
cd "$REPO_ROOT"

REALM_EXPORT="keycloak/realm-export.json"
[[ -f "$REALM_EXPORT" ]] || { echo "ERROR: $REALM_EXPORT not found at repo root" >&2; exit 2; }

# Per-run temp file for the PUT response body. Unique path prevents clobber
# across concurrent runs; trap guarantees cleanup on any exit path (success,
# error, signal). mktemp is in coreutils so it's everywhere bash + jq are.
PUT_BODY_FILE="$(mktemp -t fix-hms-portal-uris.body.XXXXXX)"
trap 'rm -f "$PUT_BODY_FILE"' EXIT INT TERM

case "$ENV_FILTER" in
  dev)  HOST="https://hms-keycloak-dev-dev.up.railway.app" ;;
  uat)  HOST="https://hms-keycloak-uat-uat.up.railway.app" ;;
  prod) HOST="https://hms-keycloak-prod-prod.up.railway.app" ;;
esac

echo "[fix-hms-portal-uris] target: ${HOST} (realm: hms, client: hms-portal)"
echo

# ─── Authenticate ────────────────────────────────────────────────────────────
read -rp "[${ENV_FILTER}] master admin username: " ADMIN_USER
[[ -z "$ADMIN_USER" ]] && { echo "ERROR: username required"; exit 2; }
read -rsp "[${ENV_FILTER}] password for ${ADMIN_USER}: " ADMIN_PASS; echo

# Capture body + http_code so we can distinguish network/TLS errors (curl exit
# code) from auth-shaped 4xx (HTTP error body) from a real token. Pass through
# curl stderr (no 2>/dev/null) so TLS / connect failures surface to the user;
# the password is on stdin, not in argv, so it doesn't leak.
TOKEN_RESP=$(printf '%s' "$ADMIN_PASS" | curl -sS -X POST \
  --connect-timeout 10 --max-time 30 \
  -w $'\n__HTTP__%{http_code}' \
  "${HOST}/realms/master/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=admin-cli" \
  --data-urlencode "username=${ADMIN_USER}" \
  --data-urlencode "password@-") || {
    unset ADMIN_PASS
    echo "ERROR: token endpoint unreachable (curl exited non-zero) — check network / TLS" >&2
    exit 2
  }
unset ADMIN_PASS

TOKEN_STATUS="${TOKEN_RESP##*__HTTP__}"
TOKEN_BODY="${TOKEN_RESP%$'\n'__HTTP__*}"
if [[ "$TOKEN_STATUS" != "200" ]]; then
  echo "ERROR: admin login failed — HTTP ${TOKEN_STATUS}" >&2
  echo "Body: $(printf '%s' "$TOKEN_BODY" | head -c 300)" >&2
  exit 2
fi
TOKEN=$(printf '%s' "$TOKEN_BODY" | jq -r '.access_token // empty')
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: HTTP 200 but no access_token in body — Keycloak returned an unexpected shape" >&2
  exit 2
fi
echo "  ✓ authenticated"

# ─── Fetch live hms-portal client representation ─────────────────────────────
LIVE_CLIENT=$(curl -sS -H "Authorization: Bearer $TOKEN" \
  "${HOST}/admin/realms/hms/clients?clientId=hms-portal")

# Guard: response should be a non-empty JSON array containing exactly one client.
LIVE_COUNT=$(printf '%s' "$LIVE_CLIENT" | jq 'if type=="array" then length else 0 end')
if [[ "$LIVE_COUNT" != "1" ]]; then
  echo "ERROR: expected exactly 1 hms-portal client, got: ${LIVE_COUNT}" >&2
  echo "Response body (first 500 chars):" >&2
  printf '%s' "$LIVE_CLIENT" | head -c 500 >&2
  exit 1
fi

CLIENT_UUID=$(printf '%s' "$LIVE_CLIENT" | jq -r '.[0].id // empty')
if [[ -z "$CLIENT_UUID" || "$CLIENT_UUID" == "null" ]]; then
  echo "ERROR: live hms-portal client response is missing a usable .id — PUT would target /clients/null" >&2
  echo "Response body (first 500 chars):" >&2
  printf '%s' "$LIVE_CLIENT" | head -c 500 >&2
  exit 1
fi
LIVE_OBJ=$(printf '%s' "$LIVE_CLIENT" | jq '.[0]')
echo "  ✓ hms-portal UUID: ${CLIENT_UUID}"

# ─── Extract desired values from realm-export.json ───────────────────────────
EXPORT_REDIRECT_URIS=$(jq -c '
  .clients[] | select(.clientId=="hms-portal") | .redirectUris
' "$REALM_EXPORT")

EXPORT_WEB_ORIGINS=$(jq -c '
  .clients[] | select(.clientId=="hms-portal") | .webOrigins
' "$REALM_EXPORT")

EXPORT_POST_LOGOUT=$(jq -r '
  .clients[] | select(.clientId=="hms-portal") | .attributes["post.logout.redirect.uris"]
' "$REALM_EXPORT")

# ─── Compute new client representation ───────────────────────────────────────
NEW_OBJ=$(printf '%s' "$LIVE_OBJ" | jq \
  --argjson redirects "$EXPORT_REDIRECT_URIS" \
  --argjson origins "$EXPORT_WEB_ORIGINS" \
  --arg post_logout "$EXPORT_POST_LOGOUT" '
    .redirectUris = $redirects
    | .webOrigins = $origins
    | .attributes["post.logout.redirect.uris"] = $post_logout
')

# ─── Diff ────────────────────────────────────────────────────────────────────
LIVE_TRIPLE=$(printf '%s' "$LIVE_OBJ" | jq -S '{redirectUris, webOrigins, postLogout: .attributes["post.logout.redirect.uris"]}')
NEW_TRIPLE=$(printf '%s' "$NEW_OBJ"  | jq -S '{redirectUris, webOrigins, postLogout: .attributes["post.logout.redirect.uris"]}')

if [[ "$LIVE_TRIPLE" == "$NEW_TRIPLE" ]]; then
  echo "  ✓ live hms-portal already matches realm-export.json — no change needed"
  unset TOKEN
  exit 0
fi

echo
echo "--- LIVE (before) ---"
printf '%s\n' "$LIVE_TRIPLE"
echo
echo "--- NEW  (after) ----"
printf '%s\n' "$NEW_TRIPLE"
echo

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[dry-run] not applying. Re-run without --dry-run to PUT the change."
  unset TOKEN
  exit 0
fi

# ─── Apply ───────────────────────────────────────────────────────────────────
# Use mktemp so concurrent runs don't clobber each other's response body.
# Trap ensures the temp file is removed on any exit path.
PUT_BODY_FILE=$(mktemp -t fix-hms-portal-uris.XXXXXX.body)
trap 'rm -f "$PUT_BODY_FILE"' EXIT

echo "Applying PUT to /admin/realms/hms/clients/${CLIENT_UUID} ..."
PUT_STATUS=$(printf '%s' "$NEW_OBJ" | curl -sS -X PUT \
  --connect-timeout 10 --max-time 30 \
  -w '%{http_code}' -o "$PUT_BODY_FILE" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @- \
  "${HOST}/admin/realms/hms/clients/${CLIENT_UUID}")

if [[ "$PUT_STATUS" != "204" ]]; then
  echo "ERROR: PUT returned HTTP ${PUT_STATUS}" >&2
  echo "Body:" >&2
  cat "$PUT_BODY_FILE" >&2 || true
  unset TOKEN
  exit 1
fi
echo "  ✓ PUT 204"

# ─── Verify ──────────────────────────────────────────────────────────────────
sleep 1
VERIFY_OBJ=$(curl -sS -H "Authorization: Bearer $TOKEN" \
  "${HOST}/admin/realms/hms/clients?clientId=hms-portal" | jq '.[0]')
VERIFY_TRIPLE=$(printf '%s' "$VERIFY_OBJ" | jq -S '{redirectUris, webOrigins, postLogout: .attributes["post.logout.redirect.uris"]}')

if [[ "$VERIFY_TRIPLE" == "$NEW_TRIPLE" ]]; then
  echo "  ✓ verified: live hms-portal now matches realm-export.json"
  echo
  echo "Next: rerun env-sync-verify to confirm A1 PASS:"
  echo "  bash scripts/keycloak/env-sync-verify.sh --full --env ${ENV_FILTER}"
else
  echo "WARN: verify did not match expected. Inspect manually." >&2
  echo "expected:" >&2; printf '%s\n' "$NEW_TRIPLE" >&2
  echo "got:" >&2;      printf '%s\n' "$VERIFY_TRIPLE" >&2
  unset TOKEN
  exit 1
fi

unset TOKEN
