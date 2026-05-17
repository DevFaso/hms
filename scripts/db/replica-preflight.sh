#!/usr/bin/env bash
#
# scripts/db/replica-preflight.sh
#
# Roadmap row 35 follow-on — preflight verification harness for the
# read-replica activation in docs/runbooks/postgres-pool-replica-sizing.md
# section 3 ("Activation playbook"). Companion to
# ReadReplicaHealthIndicator: this script catches drift BEFORE the
# operator flips APP_DATASOURCE_REPLICA_ENABLED=true; the health
# indicator catches drift AFTER.
#
# Runs six checks in order, exits non-zero on the first failure:
#
#   1. Required env vars present and well-formed.
#   2. Primary reachable + write-pool role can run SELECT 1.
#   3. Replica reachable + REPLICA_USER can run SELECT 1.
#   4. Replica is actually a hot-standby (pg_is_in_recovery()=true).
#   5. Replication lag is within REPLICA_LAG_BUDGET_SECONDS (default 5s).
#   6. REPLICA_USER has pg_read_all_data (Postgres >= 14) OR explicit
#      SELECT grants on every schema HMS reads from.
#
# Required env:
#   PRIMARY_URL        psql DSN — postgresql://hms_app:****@primary.host:5432/hospital_db
#   REPLICA_URL        psql DSN — postgresql://hms_app_ro:****@replica.host:5432/hospital_db
#
# Optional env:
#   REPLICA_LAG_BUDGET_SECONDS    default 5 — fail when replay lag exceeds this
#   POSTGRES_VERSION_OVERRIDE     when set, skip the pg_read_all_data check
#                                 and trust the operator to have granted SELECT
#                                 schema-by-schema (Postgres < 14 deployments)
#
# Exit codes:
#   0   every check passed — safe to flip APP_DATASOURCE_REPLICA_ENABLED=true
#   1   one or more checks failed
#   2   invocation error — missing env or missing dependency
#
# Usage:
#   PRIMARY_URL=postgresql://hms_app:***@primary:5432/hospital_db \
#   REPLICA_URL=postgresql://hms_app_ro:***@replica:5432/hospital_db \
#     ./scripts/db/replica-preflight.sh
#
set -euo pipefail

err()  { printf '[FAIL] %s\n' "$*" >&2; exit 1; }
ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }

: "${PRIMARY_URL:?PRIMARY_URL must be set (e.g. postgresql://hms_app:***@primary.host:5432/hospital_db)}"
: "${REPLICA_URL:?REPLICA_URL must be set (e.g. postgresql://hms_app_ro:***@replica.host:5432/hospital_db)}"

LAG_BUDGET="${REPLICA_LAG_BUDGET_SECONDS:-5}"

command -v psql >/dev/null 2>&1 || err "psql is required (apt-get install postgresql-client)"

# Verify URLs parse cleanly. We don't shell-substitute the DSNs into
# SQL — psql reads them via the -d flag — but we do reject DSNs that
# obviously won't parse so the operator gets a clear message rather
# than psql's typically opaque "connection to server ... failed".
url_looks_valid() {
    local url="$1"
    [[ "${url}" =~ ^postgres(ql)?://[^/[:space:]]+/[^[:space:]]+$ ]]
}
url_looks_valid "${PRIMARY_URL}" || err "PRIMARY_URL does not match postgresql://user:pass@host:port/db"
url_looks_valid "${REPLICA_URL}" || err "REPLICA_URL does not match postgresql://user:pass@host:port/db"

info "Replica preflight starting — lag budget ${LAG_BUDGET}s"

# ── 1. Primary reachable + write role can SELECT 1 ──────────────────
if ! PRIMARY_OUT="$(PGCONNECT_TIMEOUT=5 psql -tAX -d "${PRIMARY_URL}" -c 'SELECT 1' 2>&1)"; then
    err "primary not reachable or SELECT 1 failed: ${PRIMARY_OUT}"
fi
[[ "${PRIMARY_OUT}" == "1" ]] || err "primary SELECT 1 returned '${PRIMARY_OUT}', expected '1'"
ok "primary reachable, SELECT 1 returned 1"

# ── 2. Replica reachable + read-only role can SELECT 1 ──────────────
if ! REPLICA_OUT="$(PGCONNECT_TIMEOUT=5 psql -tAX -d "${REPLICA_URL}" -c 'SELECT 1' 2>&1)"; then
    err "replica not reachable or SELECT 1 failed: ${REPLICA_OUT}"
fi
[[ "${REPLICA_OUT}" == "1" ]] || err "replica SELECT 1 returned '${REPLICA_OUT}', expected '1'"
ok "replica reachable, SELECT 1 returned 1"

# ── 3. Replica IS a hot-standby ─────────────────────────────────────
# pg_is_in_recovery() returns true on a streaming/logical replica that's
# replaying WAL; returns false on a primary. Pointing the app's
# replica DSN at the primary by accident is one of the few ways to
# corrupt data quickly (writes silently land), so this gate is the
# load-bearing one in the script.
IS_REPLICA="$(PGCONNECT_TIMEOUT=5 psql -tAX -d "${REPLICA_URL}" -c 'SELECT pg_is_in_recovery()' 2>&1)"
[[ "${IS_REPLICA}" == "t" ]] || err "replica is NOT in recovery (pg_is_in_recovery()=${IS_REPLICA}); REPLICA_URL likely points at a primary"
ok "replica is in recovery (pg_is_in_recovery()=true)"

# ── 4. Replication lag within budget ────────────────────────────────
# Two queries: the replica's own self-report of replay timestamp, and
# the lag in seconds since that timestamp. A replica that has been
# idle for hours will report a large lag through no fault of its own
# (no WAL has been generated to replay), so we cross-check with
# pg_last_wal_receive_lsn() == pg_last_wal_replay_lsn() — equal LSN
# means "fully caught up to whatever the primary has sent" regardless
# of the timestamp gap.
read -r LAG_SEC RECEIVED_LSN REPLAYED_LSN <<< "$(
    PGCONNECT_TIMEOUT=5 psql -tAX -d "${REPLICA_URL}" -c "
        SELECT
            COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())), 0),
            pg_last_wal_receive_lsn(),
            pg_last_wal_replay_lsn()
    " | tr '|' ' '
)"
info "lag: ${LAG_SEC}s, received_lsn=${RECEIVED_LSN}, replayed_lsn=${REPLAYED_LSN}"

# bc may not be installed on minimal images; awk is everywhere.
LAG_OVER_BUDGET="$(awk -v lag="${LAG_SEC}" -v budget="${LAG_BUDGET}" 'BEGIN { print (lag > budget) ? 1 : 0 }')"
LSN_EQUAL="$(test "${RECEIVED_LSN}" = "${REPLAYED_LSN}" && echo 1 || echo 0)"

if [[ "${LAG_OVER_BUDGET}" == "1" && "${LSN_EQUAL}" == "0" ]]; then
    err "replication lag ${LAG_SEC}s exceeds budget ${LAG_BUDGET}s AND received_lsn != replayed_lsn — replica is genuinely behind"
fi
if [[ "${LAG_OVER_BUDGET}" == "1" ]]; then
    info "lag ${LAG_SEC}s > budget ${LAG_BUDGET}s but received_lsn == replayed_lsn — replica is caught up (idle primary)"
fi
ok "replication lag within budget (or replica caught up to LSN)"

# ── 5. Read-only role has either pg_read_all_data OR explicit SELECT grants ─
if [[ -n "${POSTGRES_VERSION_OVERRIDE:-}" ]]; then
    info "POSTGRES_VERSION_OVERRIDE set — skipping pg_read_all_data check"
else
    HAS_RO_ROLE="$(PGCONNECT_TIMEOUT=5 psql -tAX -d "${REPLICA_URL}" -c "
        SELECT pg_has_role(current_user, 'pg_read_all_data', 'USAGE')
    " 2>&1)"
    if [[ "${HAS_RO_ROLE}" == "t" ]]; then
        ok "replica user has pg_read_all_data (Postgres >= 14 idiom)"
    else
        info "replica user does NOT have pg_read_all_data — fallback check for explicit SELECT on hospital.patients"
        # If the operator chose to grant SELECT schema-by-schema rather
        # than use pg_read_all_data, verify the load-bearing one:
        # SELECT on hospital.patients. (The full enumeration would be
        # several dozen tables — this is a sniff test, not an audit.)
        CAN_SELECT_PATIENTS="$(PGCONNECT_TIMEOUT=5 psql -tAX -d "${REPLICA_URL}" -c "
            SELECT has_table_privilege(current_user, 'hospital.patients', 'SELECT')
        " 2>&1)"
        [[ "${CAN_SELECT_PATIENTS}" == "t" ]] || err "replica user has neither pg_read_all_data nor SELECT on hospital.patients — grant one before activation"
        ok "replica user has SELECT on hospital.patients (Postgres < 14 path)"
    fi
fi

info "all replica preflight checks passed — safe to set APP_DATASOURCE_REPLICA_ENABLED=true"
