# syntax=docker/dockerfile:1
#
# Production Keycloak image for HMS.
#
# This Dockerfile is the infra-as-code artefact consumed by Railway when the
# "hms-keycloak" service is provisioned (see keycloak/prod/README.md). It is
# NOT used by the local docker-compose profile — that still points at the
# upstream quay.io/keycloak/keycloak image directly.
#
# Build-time steps (the Keycloak "build" phase) bake in the DB vendor and
# features so boot is fast. Secrets (admin credentials, DB URL, hostname,
# HTTPS cert references) are supplied at RUN time via Railway env vars.
#
# Upstream reference: https://www.keycloak.org/server/containers
#
FROM quay.io/keycloak/keycloak:26.0.7 AS builder

ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

# Copy the HMS realm so it is importable at first boot. Dev-only users are
# deliberately NOT copied here — the `realm-export.dev-users.json` file
# must never end up in production.
COPY keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json

RUN /opt/keycloak/bin/kc.sh build

# --- Final image ---------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.0.7

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Railway injects $PORT; Keycloak serves on $KC_HTTP_PORT. We map them via
# the entrypoint below so the app binds to whatever port Railway assigns.
ENV KC_PROXY_HEADERS=xforwarded \
    KC_HTTP_ENABLED=true \
    KC_HOSTNAME_STRICT=true \
    KC_HOSTNAME_STRICT_HTTPS=true \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

# Railway's healthcheck hits /health/ready (proxied from the backend).
EXPOSE 8080

# Import the realm on every boot so realm-export.json changes are picked up.
# `--optimized` skips the build step at runtime (already baked in above).
ENTRYPOINT ["/bin/sh", "-c", "exec /opt/keycloak/bin/kc.sh start --optimized --import-realm --http-port=${PORT:-8080}"]
