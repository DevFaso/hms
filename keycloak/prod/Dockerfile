# syntax=docker/dockerfile:1
#
# Keycloak image for HMS — serves dev / uat / prod via BUILD_CONFIG.
#
# This Dockerfile is the infra-as-code artefact consumed by Railway when each
# `hms-keycloak-{dev,uat,prod}` service is provisioned (see README.md). One
# Dockerfile, one railway.toml, three Railway services — each service sets
# `BUILD_CONFIG=<env>` plus its own runtime vars (KC_HOSTNAME, KC_DB_*).
#
# Dev users (`realm-export.dev-users.json`) are deliberately NOT baked in,
# even for BUILD_CONFIG=dev — that file's `_comment` explicitly forbids
# auto-import to prevent stray staff accounts in shared environments. Apply
# them manually via the admin console's Partial Import when needed.
#
# Build-time steps (the Keycloak "build" phase) bake in the DB vendor and
# features so boot is fast. Secrets (admin credentials, DB URL, hostname,
# HTTPS cert references) are supplied at RUN time via Railway env vars.
#
# Upstream reference: https://www.keycloak.org/server/containers
#
ARG BUILD_CONFIG=prod

FROM quay.io/keycloak/keycloak:26.0.7 AS builder

ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

# Realm definition is identical across envs; per-env data lives in each
# service's Postgres + the migration script's output.
COPY keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json

RUN /opt/keycloak/bin/kc.sh build

# --- Final image ---------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.0.7

ARG BUILD_CONFIG
LABEL com.bitnesttechs.hms.env="${BUILD_CONFIG}"

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Railway injects $PORT; Keycloak serves on $KC_HTTP_PORT. We map them via
# the entrypoint below so the app binds to whatever port Railway assigns.
#
# KC_HMS_ENV is grep-able from boot logs and a useful tag for the metrics
# scrape; it has no functional effect on Keycloak itself.
ENV KC_PROXY_HEADERS=xforwarded \
    KC_HTTP_ENABLED=true \
    KC_HOSTNAME_STRICT=true \
    KC_HOSTNAME_STRICT_HTTPS=true \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_HMS_ENV=${BUILD_CONFIG}

# Railway's healthcheck hits /health/ready (proxied from the backend).
EXPOSE 8080

# Import the realm on every boot so realm-export.json changes are picked up.
# `--optimized` skips the build step at runtime (already baked in above).
#
# Admin bootstrap: KC_BOOTSTRAP_ADMIN_* env vars and the `--bootstrap-admin-*`
# start flags are both honored only at very first realm initialization in
# Keycloak 26. Once the master realm exists in the DB — even if no admin role
# was assigned because the first boot crashed mid-startup — KC silently
# ignores both, leaving the env permanently unmanageable.
#
# The recovery mechanism Keycloak ships for this case is the standalone
# `kc.sh bootstrap-admin user` subcommand, which writes directly to the
# master realm tables and works on every invocation regardless of realm
# state. We invoke it before `kc.sh start`:
#
#   - Guarded by an `[ -n ]` check so an env that has already removed
#     the bootstrap creds (per the README "Lock down the admin console"
#     step, after creating a named admin) skips this entirely.
#   - `--password:env` reads the password from the env var rather than
#     accepting it on the command line, so it never appears in `ps aux`.
#   - `--optimized` is REQUIRED. Without it, kc.sh re-evaluates configuration
#     from environment variables in the bootstrap subshell, doesn't see the
#     build-time KC_DB=postgres, and rebuilds the optimized image with
#     KC_DB=dev-file. That rebuild silently strips the Postgres JDBC driver,
#     so the next `kc.sh start` fails with "Driver does not support the
#     provided URL: jdbc:postgresql://…". Keycloak itself prints the correct
#     incantation in its own startup log: see Keycloak Quarkus mappers output.
#   - The non-zero-exit handling tries to distinguish "admin already exists"
#     (legitimate idempotent re-run) from real failures (DB unreachable,
#     password too weak, config invalid). Real failures cause an immediate
#     `exit 1` so Railway's healthcheck flips red in seconds rather than
#     spending 5 minutes retrying a server that can't connect to its DB.
#
# Once the named admin is in place and the bootstrap creds are removed
# from Railway, this whole block becomes a no-op.
# --chmod=0755 makes the script executable at COPY time. A separate `RUN chmod`
# step would fail because the Keycloak base image runs as a non-root user
# without write permission on /opt/keycloak/bin. BuildKit syntax (enabled at
# the top of this file) honors --chmod since dockerfile/1.x.
COPY --chmod=0755 keycloak/prod/entrypoint.sh /opt/keycloak/bin/hms-entrypoint.sh
ENTRYPOINT ["/opt/keycloak/bin/hms-entrypoint.sh"]
