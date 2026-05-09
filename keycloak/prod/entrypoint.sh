#!/bin/sh
# HMS Keycloak entrypoint (dev / uat / prod via BUILD_CONFIG).
#
# Two phases:
#   1. Optional admin bootstrap via `kc.sh bootstrap-admin user --optimized`.
#      Runs only when KC_BOOTSTRAP_ADMIN_USERNAME + _PASSWORD are both set.
#      Idempotent: a non-zero exit caused by "admin already exists" is treated
#      as success; any other non-zero (DB unreachable, config invalid, password
#      too weak) is treated as a real failure and aborts the container so
#      Railway's healthcheck flips red within seconds instead of spending five
#      minutes retrying a Keycloak that can't connect to its database.
#   2. `kc.sh start --optimized --import-realm --http-port="$PORT"` so
#      Keycloak binds to whatever port Railway assigns. PORT is forwarded
#      via the --http-port CLI flag rather than being exported into
#      KC_HTTP_PORT — both forms work, the flag form is one fewer env var
#      in the runtime contract and is what `kc.sh start` parses directly.
#
# Lives at /opt/keycloak/bin/hms-entrypoint.sh in the image (see Dockerfile).
# /bin/sh, not bash — the upstream Keycloak base image ships bash but we keep
# this POSIX-portable in case the base ever changes.

set -eu

log() {
    # Tagged so the lines are easy to grep in Railway / Splunk:
    #   [hms-keycloak-entrypoint]
    printf '[hms-keycloak-entrypoint] %s\n' "$*"
}

# ─── 1. Admin bootstrap ────────────────────────────────────────────────────
if [ -n "${KC_BOOTSTRAP_ADMIN_USERNAME:-}" ] && [ -n "${KC_BOOTSTRAP_ADMIN_PASSWORD:-}" ]; then
    log "Attempting kc.sh bootstrap-admin user (idempotent, --optimized)"

    # Capture combined stdout+stderr so we can grep the output for
    # "already exists" patterns. --optimized is REQUIRED — without it kc.sh
    # rebuilds the optimized image with db=dev-file and strips the Postgres
    # driver, breaking every subsequent start.
    bootstrap_output=$(/opt/keycloak/bin/kc.sh bootstrap-admin user \
        --username "${KC_BOOTSTRAP_ADMIN_USERNAME}" \
        --password:env KC_BOOTSTRAP_ADMIN_PASSWORD \
        --no-prompt \
        --optimized 2>&1) && bootstrap_rc=0 || bootstrap_rc=$?

    # Print whatever the subcommand said so operators can read it from the
    # container logs. Don't echo the password (the --password:env flag
    # already prevents that).
    printf '%s\n' "${bootstrap_output}"

    if [ "${bootstrap_rc}" -eq 0 ]; then
        log "bootstrap-admin succeeded"
    elif printf '%s' "${bootstrap_output}" | grep -qiE 'user .* already exists|admin .* exists|user with username .* already exists'; then
        # Idempotent re-run on an env where the admin was created in a
        # previous deploy. This is the expected steady state.
        log "bootstrap-admin reports admin already exists — continuing"
    else
        # Real failure (DB unreachable, password policy violation, malformed
        # config). Fail fast — `set -e` plus an explicit exit so the
        # container terminates rather than limping into kc.sh start.
        log "bootstrap-admin FAILED with rc=${bootstrap_rc} — aborting"
        log "Most common cause: missing --optimized flag triggers a rebuild"
        log "with db=dev-file, stripping the Postgres driver. If you see"
        log "'Driver does not support the provided URL' above, that's it."
        exit "${bootstrap_rc}"
    fi
else
    log "No KC_BOOTSTRAP_ADMIN_* env vars — skipping admin bootstrap"
fi

# ─── 2. Server start ───────────────────────────────────────────────────────
# Railway injects $PORT; Keycloak listens on $KC_HTTP_PORT. Map them here so
# we don't need to also set KC_HTTP_PORT in Railway env vars.
log "Starting Keycloak on port ${PORT:-8080}"
exec /opt/keycloak/bin/kc.sh start \
    --optimized \
    --import-realm \
    --http-port="${PORT:-8080}"
