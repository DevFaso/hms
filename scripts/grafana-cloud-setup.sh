#!/usr/bin/env bash
#
# Point an HMS backend service at Grafana Cloud.
#
#   ./scripts/grafana-cloud-setup.sh <dev|prod> <glc_access_policy_token>
#
# Sets the OTEL/Micrometer variables documented in docs/GRAFANA_CLOUD_SETUP.md on
# the matching Railway service. The token is written to Railway and never echoed,
# committed, or stored locally.
#
# Deliberately does NOT deploy: promoting a config change to prod is the
# operator's call, not the script's. Railway picks the new variables up on the
# next deploy.

set -euo pipefail

# ── Grafana Cloud stack (instance 1404501, zone prod-us-east-2) ──────────────
readonly GRAFANA_INSTANCE_ID="1404501"
readonly OTLP_ENDPOINT="https://otlp-gateway-prod-us-east-2.grafana.net/otlp"

usage() {
    cat >&2 <<USAGE
Usage: $0 <dev|prod> <glc_token>

Arguments:
  dev|prod    Which Railway environment to configure.
  glc_token   Grafana Cloud access policy token. Needs metrics:write,
              logs:write and traces:write, and nothing else.
              Mint it at: Grafana Cloud -> Administration -> Users and access
              -> Access policies.

Example:
  $0 dev glc_eyJvIjoi...

See docs/GRAFANA_CLOUD_SETUP.md for what this sets and why.
USAGE
    exit 64
}

[[ $# -eq 2 ]] || usage

readonly ENVIRONMENT="$1"
readonly GLC_TOKEN="$2"

case "$ENVIRONMENT" in
    dev|prod) ;;
    *) echo "error: environment must be 'dev' or 'prod', got '$ENVIRONMENT'" >&2; usage ;;
esac

if [[ "$GLC_TOKEN" != glc_* ]]; then
    echo "error: the token does not start with 'glc_'. Grafana Cloud access policy" >&2
    echo "       tokens always do — check you did not paste a stack URL or a user id." >&2
    exit 64
fi

command -v railway >/dev/null 2>&1 || {
    echo "error: the railway CLI is not on PATH. Install it with 'npm i -g @railway/cli'." >&2
    exit 69
}

readonly SERVICE="hms-backend-${ENVIRONMENT}"

# base64 with no line wrapping: a wrapped header is silently rejected by the
# gateway as malformed, which looks exactly like a bad token.
AUTH_B64="$(printf '%s:%s' "$GRAFANA_INSTANCE_ID" "$GLC_TOKEN" | base64 | tr -d '\n')"
readonly AUTH_B64

# Traces default to 100% in the agent. Fine on dev; on prod that is the one
# signal that can outgrow the free tier, so sample it.
if [[ "$ENVIRONMENT" == "prod" ]]; then
    readonly TRACES_SAMPLER="parentbased_traceidratio"
    readonly TRACES_SAMPLER_ARG="0.1"
else
    readonly TRACES_SAMPLER="parentbased_always_on"
    readonly TRACES_SAMPLER_ARG=""
fi

echo "Configuring Grafana Cloud export for ${SERVICE}"
echo "  stack instance : ${GRAFANA_INSTANCE_ID}"
echo "  OTLP endpoint  : ${OTLP_ENDPOINT}"
echo "  traces sampler : ${TRACES_SAMPLER}${TRACES_SAMPLER_ARG:+ @ ${TRACES_SAMPLER_ARG}}"
echo

# Non-secret variables: safe to pass on the command line.
public_args=(
    "OTEL_EXPORTER_OTLP_ENDPOINT=${OTLP_ENDPOINT}"
    "OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf"
    "OTEL_SERVICE_NAME=hms-backend"
    "OTEL_RESOURCE_ATTRIBUTES=deployment.environment=${ENVIRONMENT},service.namespace=hms"
    "OTEL_METRICS_ENABLED=true"
    "OTEL_TRACES_SAMPLER=${TRACES_SAMPLER}"
)
[[ -n "$TRACES_SAMPLER_ARG" ]] && public_args+=("OTEL_TRACES_SAMPLER_ARG=${TRACES_SAMPLER_ARG}")

# --skip-deploys keeps this a config-only change; the operator decides when the
# service restarts. Without it, Railway redeploys prod the moment this runs.
railway variable set \n    --service "$SERVICE" \n    --environment "$ENVIRONMENT" \n    --skip-deploys \n    "${public_args[@]}"

# The two credential-bearing variables go in via stdin so the token never lands
# in the process list, the shell history, or this script's own output.
printf 'Authorization=Basic %s' "$AUTH_B64" \n    | railway variable set OTEL_EXPORTER_OTLP_HEADERS \n        --stdin --service "$SERVICE" --environment "$ENVIRONMENT" --skip-deploys

printf 'Basic %s' "$AUTH_B64" \n    | railway variable set GRAFANA_OTLP_AUTH_HEADER \n        --stdin --service "$SERVICE" --environment "$ENVIRONMENT" --skip-deploys

echo
echo "Set on ${SERVICE}:"
for kv in "${public_args[@]}"; do
    echo "  ${kv}"
done
echo "  OTEL_EXPORTER_OTLP_HEADERS=<redacted: Basic base64(${GRAFANA_INSTANCE_ID}:glc_...)>"
echo "  GRAFANA_OTLP_AUTH_HEADER=<redacted: Basic base64(${GRAFANA_INSTANCE_ID}:glc_...)>"

cat <<NEXT

Not deployed yet. To activate:

  railway redeploy --service ${SERVICE} --environment ${ENVIRONMENT}

Then confirm the agent attached:

  railway logs --service ${SERVICE} | grep -i "OpenTelemetry agent enabled"

And query Grafana Cloud -> Explore -> Mimir:

  up{service_name="hms-backend", deployment_environment="${ENVIRONMENT}"}
NEXT
