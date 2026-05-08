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
# `--bootstrap-admin-*` flags create a master-realm admin if none exists. They
# are idempotent — Keycloak ignores them once an admin exists, so they're safe
# to leave in. Sourced from KC_BOOTSTRAP_ADMIN_USERNAME / KC_BOOTSTRAP_ADMIN_PASSWORD
# env vars; the `${VAR:+value}` expansion omits the flags entirely when those
# env vars are unset (recommended posture after the named admin is in place,
# to avoid the bootstrap creds appearing in `ps aux` output).
#
# Why flags instead of env vars: KC_BOOTSTRAP_ADMIN_* are honored only on the
# very first realm initialization. Once the master realm exists in the DB, KC
# ignores those env vars on subsequent boots, leaving an env that booted
# without admin creds permanently unmanageable. The CLI flags work on every
# start when no admin exists.
ENTRYPOINT ["/bin/sh", "-c", "exec /opt/keycloak/bin/kc.sh start --optimized --import-realm --http-port=${PORT:-8080} ${KC_BOOTSTRAP_ADMIN_USERNAME:+--bootstrap-admin-username \"$KC_BOOTSTRAP_ADMIN_USERNAME\"} ${KC_BOOTSTRAP_ADMIN_PASSWORD:+--bootstrap-admin-password \"$KC_BOOTSTRAP_ADMIN_PASSWORD\"}"]
