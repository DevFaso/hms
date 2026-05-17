#!/usr/bin/env bash
#
# scripts/tenancy/invalidate-tenant-cache.sh
#
# Roadmap row 33 follow-on (v2.0 / Multi-tenancy / Schema-per-tenant).
# Step 4 helper for the cutover procedure documented in
#   docs/runbooks/schema-per-tenant-migration.md
#
# Calls the super-admin REST endpoint that drops one hospital from
# the TenantSchemaLookup cache so the next request resolves to the
# new schema immediately, instead of waiting up to 5 min for the TTL
# to expire. Mirrors what the operator would do via curl by hand —
# this wrapper just centralises the URL, auth header, and error
# handling.
#
# The endpoint is flag-gated on app.tenancy.schema-isolation.enabled.
# When the flag is off it returns 404 — a rolling pod restart is the
# only way to clear the cache, and this script bails early in that
# case rather than pretending success.
#
# Required env:
#   HMS_BACKEND_BASE_URL   e.g. https://api.hms.uat.example.com
#   HMS_ADMIN_TOKEN        super-admin bearer token (NEVER log this)
#
# Usage:
#   scripts/tenancy/invalidate-tenant-cache.sh <hospital-uuid>
#
# Exit codes:
#   0   endpoint returned 204 No Content (cache entry dropped)
#   1   endpoint returned non-success (401/403/404/500 — see stderr)
#   2   invocation error (bad args, missing env, missing dependency)
#
set -euo pipefail

err()  { printf '[FAIL] %s\n' "$*" >&2; exit 1; }
ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }

usage() {
    sed -n '3,/^set -euo/p' "$0" | sed 's/^# \?//'
}

# --- Argument parsing ---
HOSPITAL_UUID=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --*) printf 'unknown flag: %s\n' "$1" >&2; exit 2 ;;
        *)
            if [[ -z "${HOSPITAL_UUID}" ]]; then
                HOSPITAL_UUID="$1"
            else
                printf 'unexpected positional arg: %s\n' "$1" >&2; exit 2
            fi
            ;;
    esac
    shift
done

[[ -n "${HOSPITAL_UUID}" ]] || { usage >&2; exit 2; }

UUID_REGEX='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
[[ "${HOSPITAL_UUID}" =~ ${UUID_REGEX} ]] || \
    err "hospital UUID '${HOSPITAL_UUID}' is not a well-formed UUID"

command -v curl >/dev/null 2>&1 || err "curl is required"
: "${HMS_BACKEND_BASE_URL:?HMS_BACKEND_BASE_URL must be set, e.g. https://api.hms.uat.example.com}"
: "${HMS_ADMIN_TOKEN:?HMS_ADMIN_TOKEN must be set (super-admin bearer)}"

URL="${HMS_BACKEND_BASE_URL%/}/super-admin/tenancy/schema-cache/invalidate/${HOSPITAL_UUID}"

info "POST ${URL}"

# -w prints the HTTP status on its own line; -s silences progress;
# -o discards the body (the endpoint returns 204 No Content). The
# token is passed only in the header, never in the URL or logged.
STATUS=$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer ${HMS_ADMIN_TOKEN}" \
    -H "Accept: application/json" \
    --max-time 15 \
    "${URL}") || err "curl failed (network error or timeout)"

case "${STATUS}" in
    204) ok  "cache entry dropped (204 No Content)"; exit 0 ;;
    200) ok  "cache entry dropped (200 OK)"; exit 0 ;;
    401) err "401 Unauthorized — HMS_ADMIN_TOKEN is missing, expired, or wrong audience" ;;
    403) err "403 Forbidden — token does not carry SUPER_ADMIN role" ;;
    404) err "404 Not Found — schema-isolation flag is OFF in this env (app.tenancy.schema-isolation.enabled=false) OR the backend is on an older build that pre-dates this endpoint" ;;
    *)   err "unexpected status ${STATUS} — check backend logs" ;;
esac
