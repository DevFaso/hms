#!/usr/bin/env bash
#
# scripts/tenancy/copy-rows.sh
#
# Roadmap row 33 follow-on (v2.0 / Multi-tenancy / Schema-per-tenant).
# Step 2 of the cutover procedure documented in
#   docs/runbooks/schema-per-tenant-migration.md
#
# Copies one hospital's clinical / billing / lab rows from the shared
# global schemas into the per-tenant schema that was provisioned by
# provision-schema.sh. Source rows are NOT deleted — the cutover keeps
# the global rows in place until Step 6 (post-soak), so a rollback is
# a single UPDATE on hospital.hospitals.
#
# The whole copy runs inside a single REPEATABLE READ transaction with
# a SELECT FOR UPDATE on the hospital row, so a long copy cannot see
# writes from concurrent traffic and two cutovers cannot interleave.
#
# Table discovery: every base table in clinical / billing / lab that
# has a hospital_id column is automatically included. Discovery is
# scoped to the source schemas only (clinical, billing, lab) so an
# operator can't accidentally copy from public or reference.
#
# Required env:
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD  standard libpq
#   PGUSER must be a role with SELECT on the source schemas AND
#   INSERT on the target schema. The DDL role used by provision-schema
#   typically satisfies both.
#
# Usage:
#   scripts/tenancy/copy-rows.sh <hospital-uuid> <target-schema>
#   scripts/tenancy/copy-rows.sh --dry-run <hospital-uuid> <target-schema>
#
# Exit codes:
#   0   copy + verification clean (every source count == destination)
#   1   business logic failure (psql error OR count mismatch — Step 2
#       runbook says: abort, drop the schema, restart)
#   2   invocation error (bad args, missing dependency, validation)
#
set -euo pipefail

err()  { printf '[FAIL] %s\n' "$*" >&2; exit 1; }
warn() { printf '[WARN] %s\n' "$*" >&2; }
ok()   { printf '[ OK ] %s\n' "$*"; }
info() { printf '[INFO] %s\n' "$*"; }

usage() {
    sed -n '3,/^set -euo/p' "$0" | sed 's/^# \?//'
}

# --- Argument parsing ---
DRY_RUN=0
HOSPITAL_UUID=""
SCHEMA_NAME=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help) usage; exit 0 ;;
        --*) printf 'unknown flag: %s\n' "$1" >&2; exit 2 ;;
        *)
            if [[ -z "${HOSPITAL_UUID}" ]]; then
                HOSPITAL_UUID="$1"
            elif [[ -z "${SCHEMA_NAME}" ]]; then
                SCHEMA_NAME="$1"
            else
                printf 'unexpected positional arg: %s\n' "$1" >&2; exit 2
            fi
            ;;
    esac
    shift
done

[[ -n "${HOSPITAL_UUID}" ]] || { usage >&2; exit 2; }
[[ -n "${SCHEMA_NAME}"   ]] || { usage >&2; exit 2; }

# --- Validation ---
UUID_REGEX='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
[[ "${HOSPITAL_UUID}" =~ ${UUID_REGEX} ]] || \
    err "hospital UUID '${HOSPITAL_UUID}' is not a well-formed UUID"

SAFE_REGEX='^[a-z][a-z0-9_]{0,62}$'
[[ "${SCHEMA_NAME}" =~ ${SAFE_REGEX} ]] || \
    err "schema name '${SCHEMA_NAME}' fails SAFE_IDENTIFIER regex (${SAFE_REGEX})"

case "${SCHEMA_NAME}" in
    hospital|reference|platform|security|support|public|clinical|billing|lab)
        err "schema name '${SCHEMA_NAME}' is a shared global schema — refusing to copy into it"
        ;;
esac

command -v psql >/dev/null 2>&1 || err "psql is required"
: "${PGDATABASE:?PGDATABASE must be set (libpq variable)}"
: "${PGUSER:?PGUSER must be set}"

info "Tenant row-copy starting"
info "  hospital uuid : ${HOSPITAL_UUID}"
info "  target schema : ${SCHEMA_NAME}"
info "  database      : ${PGDATABASE} @ ${PGHOST:-localhost}:${PGPORT:-5432}"

# --- Confirm hospital exists + target schema exists ---
HOSPITAL_EXISTS=$(psql -tAc \
    "SELECT 1 FROM hospital.hospitals WHERE id = '${HOSPITAL_UUID}'" || echo "")
[[ "${HOSPITAL_EXISTS}" == "1" ]] || \
    err "no row in hospital.hospitals with id '${HOSPITAL_UUID}'"

SCHEMA_EXISTS=$(psql -tAc \
    "SELECT 1 FROM information_schema.schemata WHERE schema_name = '${SCHEMA_NAME}'" || echo "")
[[ "${SCHEMA_EXISTS}" == "1" ]] || \
    err "target schema '${SCHEMA_NAME}' does not exist — run provision-schema.sh first"

# --- Discover tables to copy ---
# Every base table in clinical / billing / lab that has a hospital_id
# column. Listed alphabetically so the plan is deterministic and a
# diff between dry-run runs shows real drift.
DISCOVERY_SQL=$(cat <<'EOF'
SELECT c.table_schema || '.' || c.table_name
  FROM information_schema.columns c
  JOIN information_schema.tables  t
    ON t.table_schema = c.table_schema
   AND t.table_name   = c.table_name
 WHERE c.column_name   = 'hospital_id'
   AND c.table_schema IN ('clinical', 'billing', 'lab')
   AND t.table_type    = 'BASE TABLE'
 ORDER BY c.table_schema, c.table_name;
EOF
)
mapfile -t TABLES < <(psql -tAc "${DISCOVERY_SQL}" | sed '/^$/d')
[[ "${#TABLES[@]}" -gt 0 ]] || \
    err "no tables with hospital_id found in clinical/billing/lab — schema introspection returned empty"

info "Discovered ${#TABLES[@]} tenant-scoped tables to copy:"
for t in "${TABLES[@]}"; do
    info "  - ${t}"
done

# --- Build the copy SQL ---
# Each table's INSERT is wrapped in its own statement so a failure
# mid-batch leaves a clear "this table broke" pointer in the psql
# output (the REPEATABLE READ tx then rolls back all preceding
# INSERTs).
{
    printf 'BEGIN ISOLATION LEVEL REPEATABLE READ;\n\n'
    printf -- '-- Serialize cutovers — lock the hospital row.\n'
    printf "SELECT id FROM hospital.hospitals WHERE id = '%s' FOR UPDATE;\n\n" "${HOSPITAL_UUID}"
    for t in "${TABLES[@]}"; do
        src_schema="${t%%.*}"
        table_name="${t##*.}"
        printf -- '-- Copy %s -> %s.%s\n' "${t}" "${SCHEMA_NAME}" "${table_name}"
        printf 'INSERT INTO "%s"."%s" SELECT * FROM "%s"."%s" WHERE hospital_id = '"'"'%s'"'"';\n\n' \
            "${SCHEMA_NAME}" "${table_name}" "${src_schema}" "${table_name}" "${HOSPITAL_UUID}"
    done
    printf 'COMMIT;\n'
} > /tmp/hms-copy-rows-$$.sql
trap 'rm -f /tmp/hms-copy-rows-$$.sql /tmp/hms-copy-counts-$$.sql' EXIT

if [[ "${DRY_RUN}" -eq 1 ]]; then
    info "--dry-run: SQL that would execute (saved to /tmp/hms-copy-rows-$$.sql):"
    cat /tmp/hms-copy-rows-$$.sql
    info ""
    info "Source row counts (informational only — no rows copied):"
    for t in "${TABLES[@]}"; do
        cnt=$(psql -tAc "SELECT COUNT(*) FROM ${t} WHERE hospital_id = '${HOSPITAL_UUID}'" || echo "ERR")
        info "  ${t}: ${cnt}"
    done
    exit 0
fi

# --- Execute the copy ---
info "Executing copy (REPEATABLE READ transaction) ..."
if psql -v ON_ERROR_STOP=1 -f /tmp/hms-copy-rows-$$.sql >/dev/null; then
    ok "Copy transaction committed"
else
    err "psql exited non-zero — transaction rolled back. See stderr above."
fi

# --- Verify counts match exactly ---
info "Verifying row counts (source == destination per table) ..."
MISMATCH=0
for t in "${TABLES[@]}"; do
    src_schema="${t%%.*}"
    table_name="${t##*.}"
    src_cnt=$(psql -tAc \
        "SELECT COUNT(*) FROM \"${src_schema}\".\"${table_name}\" WHERE hospital_id = '${HOSPITAL_UUID}'" \
        || echo "ERR")
    dst_cnt=$(psql -tAc \
        "SELECT COUNT(*) FROM \"${SCHEMA_NAME}\".\"${table_name}\"" \
        || echo "ERR")
    if [[ "${src_cnt}" == "${dst_cnt}" ]]; then
        ok "  ${t}: src=${src_cnt} dst=${dst_cnt}"
    else
        warn "  ${t}: src=${src_cnt} dst=${dst_cnt}  ← MISMATCH"
        MISMATCH=1
    fi
done

if [[ "${MISMATCH}" -eq 1 ]]; then
    err "row-count verification failed — per runbook: abort, drop the schema, restart"
fi

cat <<NEXT

[NEXT STEP]
  Copy + verification clean. The hospital is now ready for cutover
  Step 3 (drain in-flight requests) and Step 4 (flip isolation_mode
  + invalidate cache).

  Step 4 cache invalidation:
      scripts/tenancy/invalidate-tenant-cache.sh ${HOSPITAL_UUID}

  See: docs/runbooks/schema-per-tenant-migration.md § "Step 3" / "Step 4"
NEXT
