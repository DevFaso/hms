#!/usr/bin/env bash
#
# scripts/tenancy/provision-schema.sh
#
# Roadmap row 33 follow-on (v2.0 / Multi-tenancy / Schema-per-tenant).
# Step 1 of the cutover procedure documented in
#   docs/runbooks/schema-per-tenant-migration.md
#
# Creates an empty PostgreSQL schema for one hospital that has been
# selected to move from the default ROW_LEVEL multi-tenancy topology
# into a dedicated schema. Idempotent: re-running against an existing
# schema is a no-op (CREATE SCHEMA IF NOT EXISTS + GRANT, both
# idempotent). The hospital row itself is NOT modified here — the
# cutover flips isolation_mode / tenant_schema_name only after the
# copy step soaks clean (Step 4 of the runbook).
#
# Foundation-pass scope:
#   1. Validate inputs (schema name against the
#      SchemaTenantConnectionProvider#SAFE_IDENTIFIER regex).
#   2. CREATE SCHEMA IF NOT EXISTS <schema> AUTHORIZATION <ddl-role>.
#   3. GRANT USAGE on the schema + default privileges on future tables
#      to the runtime app role.
#   4. Print the next-step pointer (per-tenant Liquibase bootstrap).
#
# Per-tenant Liquibase bootstrap (creating the clinical / billing /
# lab tables INSIDE the new schema) is deliberately deferred to a
# follow-on PR — it requires splitting the existing V1 changelog into
# a tenant-scoped context, which is a multi-week migration in its own
# right. Until then, an operator runs psql against the schema and
# applies the per-tenant DDL by hand from a captured changelog.
#
# Required env:
#   PGHOST, PGPORT, PGDATABASE  standard libpq variables (or PGURL)
#   PGUSER                      DDL role (typically the LIQUIBASE_USERNAME
#                               role — needs CREATE on the database)
#   PGPASSWORD                  or use ~/.pgpass / PGSERVICE
#   HMS_APP_ROLE                the runtime role that owns DML; granted
#                               USAGE on the schema (default: hms_app)
#
# Usage:
#   scripts/tenancy/provision-schema.sh <hospital-code> <schema-name>
#   scripts/tenancy/provision-schema.sh --dry-run BFQ_MIL_001 tenant_bfq_mil_001
#
# Exit codes:
#   0   schema provisioned (or already existed) + grants applied
#   1   business logic failure (psql error, grant failure)
#   2   invocation error (bad args, missing dependency, validation fail)
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
HOSPITAL_CODE=""
SCHEMA_NAME=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help) usage; exit 0 ;;
        --*) printf 'unknown flag: %s\n' "$1" >&2; exit 2 ;;
        *)
            if [[ -z "${HOSPITAL_CODE}" ]]; then
                HOSPITAL_CODE="$1"
            elif [[ -z "${SCHEMA_NAME}" ]]; then
                SCHEMA_NAME="$1"
            else
                printf 'unexpected positional arg: %s\n' "$1" >&2; exit 2
            fi
            ;;
    esac
    shift
done

[[ -n "${HOSPITAL_CODE}" ]] || { usage >&2; exit 2; }
[[ -n "${SCHEMA_NAME}"   ]] || { usage >&2; exit 2; }

# --- Validation: schema-name regex must match the Java-side allowlist ---
# Mirrors SchemaTenantConnectionProvider#SAFE_IDENTIFIER so the script
# cannot create a schema the application would later refuse to switch
# search_path into.
SAFE_REGEX='^[a-z][a-z0-9_]{0,62}$'
[[ "${SCHEMA_NAME}" =~ ${SAFE_REGEX} ]] || \
    err "schema name '${SCHEMA_NAME}' fails SAFE_IDENTIFIER regex (${SAFE_REGEX})"

# Defensive: reject the shared-schema names so the script can never
# accidentally CREATE-on-top-of one of the global schemas.
case "${SCHEMA_NAME}" in
    hospital|reference|platform|security|support|public|clinical|billing|lab)
        err "schema name '${SCHEMA_NAME}' collides with a shared global schema"
        ;;
esac

# --- Dependency check ---
command -v psql >/dev/null 2>&1 || err "psql is required"

# --- Required env ---
HMS_APP_ROLE="${HMS_APP_ROLE:-hms_app}"
# Validate HMS_APP_ROLE against the same regex so we never inject a
# rogue identifier into the GRANT statement.
[[ "${HMS_APP_ROLE}" =~ ${SAFE_REGEX} ]] || \
    err "HMS_APP_ROLE='${HMS_APP_ROLE}' fails SAFE_IDENTIFIER regex"

: "${PGDATABASE:?PGDATABASE must be set (libpq variable)}"
: "${PGUSER:?PGUSER must be set (DDL role, typically LIQUIBASE_USERNAME)}"

# Validate PGUSER against the same allowlist so a typo'd or
# adversarial DDL-role name cannot break the SQL or inject identifiers
# via the AUTHORIZATION + DEFAULT PRIVILEGES clauses. PG identifier
# rules are slightly broader than this regex (e.g. quoted mixed-case),
# but the HMS deployment convention is lowercase snake_case roles
# (hms_app, hms_liquibase) so the stricter allowlist is fine and
# matches the schema-name rule. Caught on PR #356 Copilot review (Medium).
[[ "${PGUSER}" =~ ${SAFE_REGEX} ]] || \
    err "PGUSER='${PGUSER}' fails SAFE_IDENTIFIER regex"

info "Tenant provisioning starting"
info "  hospital code     : ${HOSPITAL_CODE}"
info "  target schema     : ${SCHEMA_NAME}"
info "  ddl role (PGUSER) : ${PGUSER}"
info "  app role (DML)    : ${HMS_APP_ROLE}"
info "  database          : ${PGDATABASE} @ ${PGHOST:-localhost}:${PGPORT:-5432}"

# --- The SQL itself ---
# All identifiers are pre-validated against SAFE_IDENTIFIER so direct
# interpolation is safe (PG identifier-quoting "..." is added defensively).
read -r -d '' SQL <<EOF || true
\\set ON_ERROR_STOP on

-- 1. Create the schema, owned by the DDL role. Idempotent.
CREATE SCHEMA IF NOT EXISTS "${SCHEMA_NAME}" AUTHORIZATION "${PGUSER}";

-- 2. Grant the runtime app role USAGE on the schema. Required for
--    Hibernate connections that SET search_path=<schema>,... to
--    resolve table references inside the schema.
GRANT USAGE ON SCHEMA "${SCHEMA_NAME}" TO "${HMS_APP_ROLE}";

-- 3. Default privileges for future tables created in this schema by
--    the DDL role — the per-tenant Liquibase bootstrap (follow-on PR)
--    will create those tables AS "${PGUSER}".
ALTER DEFAULT PRIVILEGES FOR ROLE "${PGUSER}" IN SCHEMA "${SCHEMA_NAME}"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${HMS_APP_ROLE}";
ALTER DEFAULT PRIVILEGES FOR ROLE "${PGUSER}" IN SCHEMA "${SCHEMA_NAME}"
    GRANT USAGE, SELECT ON SEQUENCES TO "${HMS_APP_ROLE}";

-- 4. Smoke-readable comment so operators can confirm provenance.
COMMENT ON SCHEMA "${SCHEMA_NAME}" IS
    'Per-tenant schema for hospital code ${HOSPITAL_CODE}. Provisioned by scripts/tenancy/provision-schema.sh. See docs/runbooks/schema-per-tenant-migration.md.';
EOF

if [[ "${DRY_RUN}" -eq 1 ]]; then
    info "--dry-run: SQL that would execute:"
    printf '\n%s\n' "${SQL}"
    exit 0
fi

info "Executing SQL ..."
if printf '%s' "${SQL}" | psql -v ON_ERROR_STOP=1 >/dev/null; then
    ok "Schema '${SCHEMA_NAME}' provisioned (or already existed)"
else
    err "psql exited non-zero — see stderr above"
fi

# --- Verification ---
SCHEMA_EXISTS=$(psql -tAc "SELECT 1 FROM information_schema.schemata WHERE schema_name = '${SCHEMA_NAME}'" || echo "")
[[ "${SCHEMA_EXISTS}" == "1" ]] || err "post-create check failed: schema '${SCHEMA_NAME}' not visible"
ok "Verification: schema '${SCHEMA_NAME}' visible in information_schema"

cat <<NEXT

[NEXT STEP]
  Per-tenant DDL bootstrap is deferred to a follow-on PR. Today the
  operator applies the captured tenant-tables DDL by hand:

      psql -d "\${PGDATABASE}" \\
           -c "SET search_path TO ${SCHEMA_NAME}, public;" \\
           -f path/to/tenant-tables-bootstrap.sql

  Then move to Step 2 of the runbook (copy-rows.sh).

  See: docs/runbooks/schema-per-tenant-migration.md § "Step 1"
NEXT
