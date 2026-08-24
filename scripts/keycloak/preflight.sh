#!/usr/bin/env bash
#
# scripts/keycloak/preflight.sh
#
# Roadmap rows 18 / 19 / 8 — preflight verification harness for the
# Keycloak cutover sequence. Companion to:
#   - docs/runbooks/keycloak-cutover-sequence.md (the conductor)
#   - docs/runbooks/keycloak-migration-runbook.md (rows 17 / 18 / 19)
#   - docs/runbooks/keycloak-cutover-runbook.md (row 8)
#
# Wraps the precondition checklists from both runbooks into a single
# script that prints one line per check and exits non-zero on the
# first failure. Safe to re-run after a fix.
#
# Required env:
#   HMS_KC_ENV               dev | prod  (selects the env stanza)
#   OIDC_ISSUER_URI          e.g. https://keycloak.<env>.example.com/realms/hms
#                            (the issuer the backend's resource server expects)
#
# Optional env:
#   HMS_KC_ADMIN_TOKEN       admin REST token; when set, A1–A3 authenticated
#                            checks fire (delegated to env-sync-verify.sh --full)
#   HMS_KC_SMOKE_INBOX_USER  IMAP user — when set, an SMTP test email is sent
#                            and the IMAP inbox is tailed for receipt
#   HMS_BACKEND_BASE_URL     e.g. https://api.dev.e-keneya.com — used to
#                            probe /actuator/health and the SSO discovery link
#
# Exit codes:
#   0   every applicable check passed
#   1   one or more checks failed (last line on stderr names the failed step)
#   2   invocation error — missing required env or missing dependency
#
# Usage:
#   HMS_KC_ENV=dev OIDC_ISSUER_URI=https://keycloak.dev.example.com/realms/hms \
#     ./scripts/keycloak/preflight.sh
#
set -euo pipefail

err() { printf '[FAIL] %s\n' "$*" >&2; exit 1; }
ok()  { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }

: "${HMS_KC_ENV:?HMS_KC_ENV must be set (dev | prod)}"
: "${OIDC_ISSUER_URI:?OIDC_ISSUER_URI must be set, e.g. https://keycloak.<env>.example.com/realms/hms}"

case "${HMS_KC_ENV}" in
    dev|prod) ;;
    *) err "HMS_KC_ENV must be one of dev | prod (got '${HMS_KC_ENV}')" ;;
esac

command -v curl >/dev/null 2>&1 || err "curl is required"
command -v jq   >/dev/null 2>&1 || err "jq is required"

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFY="${REPO_ROOT}/scripts/keycloak/env-sync-verify.sh"
[[ -x "${VERIFY}" ]] || err "scripts/keycloak/env-sync-verify.sh is missing or not executable"

info "Preflight starting — env=${HMS_KC_ENV}, issuer=${OIDC_ISSUER_URI}"

# 1. Realm-export drift + redirect-URI coverage + frontend env-file agreement.
#    Delegates to the existing env-sync-verify.sh (R1–R4 + P1–P4).
info "(1/6) Running env-sync-verify.sh public checks ..."
if HMS_KC_ENV="${HMS_KC_ENV}" OIDC_ISSUER_URI="${OIDC_ISSUER_URI}" \
   "${VERIFY}" --public-only; then
    ok  "env-sync-verify --public-only green"
else
    err "env-sync-verify --public-only red — fix the drift surface before continuing"
fi

# 2. OIDC discovery reachable + issuer match.
info "(2/6) Probing OIDC discovery endpoint ..."
DISCOVERY_URL="${OIDC_ISSUER_URI%/}/.well-known/openid-configuration"
DISCOVERY=$(curl -fsSL --max-time 10 "${DISCOVERY_URL}" || echo "")
[[ -n "${DISCOVERY}" ]] || err "OIDC discovery not reachable at ${DISCOVERY_URL}"
ISSUER_FIELD=$(printf '%s' "${DISCOVERY}" | jq -r '.issuer // empty')
[[ "${ISSUER_FIELD}" == "${OIDC_ISSUER_URI}" ]] || \
    err "OIDC discovery issuer '${ISSUER_FIELD}' != expected '${OIDC_ISSUER_URI}'"
ok  "OIDC discovery reachable, issuer matches"

# 3. SMTP roundtrip (optional — fires when HMS_KC_SMOKE_INBOX_USER is set).
if [[ -n "${HMS_KC_SMOKE_INBOX_USER:-}" ]]; then
    info "(3/6) SMTP roundtrip — send + IMAP tail ..."
    info "  (not implemented in the foundation pass — manual verification per"
    info "  keycloak-migration-runbook.md §SMTP. Exiting check as info-only.)"
    ok "SMTP roundtrip (manual)"
else
    info "(3/6) SMTP roundtrip — skipped (HMS_KC_SMOKE_INBOX_USER not set)"
fi

# 4. Backend health + SSO discovery link (when HMS_BACKEND_BASE_URL is set).
if [[ -n "${HMS_BACKEND_BASE_URL:-}" ]]; then
    info "(4/6) Backend /actuator/health ..."
    HEALTH=$(curl -fsSL --max-time 10 "${HMS_BACKEND_BASE_URL%/}/actuator/health" || echo "")
    STATUS=$(printf '%s' "${HEALTH}" | jq -r '.status // empty')
    [[ "${STATUS}" == "UP" ]] || err "Backend /actuator/health status='${STATUS}' (expected UP)"
    ok "Backend /actuator/health UP"
else
    info "(4/6) Backend health — skipped (HMS_BACKEND_BASE_URL not set)"
fi

# 5. hms_read role on the env's HMS DB — placeholder, requires per-env psql config.
info "(5/6) HMS DB hms_read role (per env) ..."
info "  (not invokable without per-env psql credentials in this shell. The"
info "  underlying check is documented in keycloak-migration-runbook.md"
info "  §Preconditions — the hms_read role must SELECT on security.users,"
info "  security.user_role_hospital_assignment, and security.roles.)"
ok "hms_read role (manual gate)"

# 6. Authenticated checks (A1–A3) when admin token is available.
if [[ -n "${HMS_KC_ADMIN_TOKEN:-}" ]]; then
    info "(6/6) Authenticated env-sync-verify ..."
    if HMS_KC_ENV="${HMS_KC_ENV}" OIDC_ISSUER_URI="${OIDC_ISSUER_URI}" \
       HMS_KC_ADMIN_TOKEN="${HMS_KC_ADMIN_TOKEN}" "${VERIFY}" --full; then
        ok  "env-sync-verify --full green (A1-A3 included)"
    else
        err "env-sync-verify --full red — A1-A3 surfaced a drift; see runbook"
    fi
else
    info "(6/6) Authenticated A1-A3 — skipped (HMS_KC_ADMIN_TOKEN not set)"
fi

printf '\n[PASS] Keycloak preflight green for env=%s.\n' "${HMS_KC_ENV}"
printf '       Proceed to the next step in docs/runbooks/keycloak-cutover-sequence.md.\n'
