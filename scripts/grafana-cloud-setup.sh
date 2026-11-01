#!/usr/bin/env bash
#
# Point an HMS backend service at Grafana Cloud.
#
#   ./scripts/grafana-cloud-setup.sh <dev|prod>
#
# Sets the OTEL/Micrometer variables documented in docs/GRAFANA_CLOUD_SETUP.md on
# the matching Railway service.
#
# The token is NOT a command-line argument. argv is world-readable in the process
# list and lands in shell history, and this token can write to every signal in the
# stack. It is read from GRAFANA_CLOUD_TOKEN, or prompted for with echo off.
#
# Deliberately does NOT deploy: promoting a config change to prod is the
# operator's call, not the script's. Railway picks the new variables up on the
# next deploy.

set -euo pipefail

# ── Grafana Cloud stack ──────────────────────────────────────────────────────
# Instance 1404501, zone prod-us-east-2 — confirmed by posting to both gateways with
# the real token: us-east-2 answered 200, us-east-0 answered 502.
#
# This was briefly changed to prod-us-east-0 on the reasoning that the stack's access
# policies are all labelled prod-us-east-0 and a policy lives in its stack's region.
# That reasoning is wrong — the access-policy region label does not determine the OTLP
# gateway zone — and the original value here was right. Do not "fix" it back without
# re-running the two-gateway probe in docs/GRAFANA_CLOUD_SETUP.md.
#
# Override without editing this file:
#   GRAFANA_ZONE=prod-us-east-0 ./scripts/grafana-cloud-setup.sh dev
readonly GRAFANA_INSTANCE_ID="${GRAFANA_INSTANCE_ID:-1404501}"
readonly GRAFANA_ZONE="${GRAFANA_ZONE:-prod-us-east-2}"
readonly OTLP_ENDPOINT="https://otlp-gateway-${GRAFANA_ZONE}.grafana.net/otlp"

usage() {
    cat >&2 <<USAGE
Usage: $0 <dev|prod>

Arguments:
  dev|prod    Which Railway environment to configure.

Token:
  Read from the GRAFANA_CLOUD_TOKEN environment variable, or prompted for
  (with echo off) when that is unset. Never passed as an argument.

  The console offers scope SETS rather than individual scopes:
  'set:alloy-data-write' is the one you want — it bundles metrics:write,
  logs:write, traces:write and profiles:write. On stack ${GRAFANA_INSTANCE_ID} the
  'stack-${GRAFANA_INSTANCE_ID}-alloy' policy already carries exactly that scope.

  Grafana Cloud -> Administration -> Users and access -> Access policies
  -> stack-${GRAFANA_INSTANCE_ID}-alloy -> Add token

Examples:
  $0 dev                                   # prompts for the token
  GRAFANA_CLOUD_TOKEN="\$(cat ~/.glc)" $0 prod

See docs/GRAFANA_CLOUD_SETUP.md for what this sets and why.
USAGE
    exit 64
}

[[ $# -eq 1 ]] || usage

readonly ENVIRONMENT="$1"

case "$ENVIRONMENT" in
    dev|prod) ;;
    *) echo "error: environment must be 'dev' or 'prod', got '$ENVIRONMENT'" >&2; usage ;;
esac

command -v railway >/dev/null 2>&1 || {
    echo "error: the railway CLI is not on PATH. Install it with 'npm i -g @railway/cli'." >&2
    exit 69
}

# ── Token ────────────────────────────────────────────────────────────────────
GLC_TOKEN="${GRAFANA_CLOUD_TOKEN:-}"
if [[ -z "$GLC_TOKEN" ]]; then
    if [[ ! -t 0 ]]; then
        echo "error: no GRAFANA_CLOUD_TOKEN set and stdin is not a terminal, so the" >&2
        echo "       token cannot be prompted for. Export GRAFANA_CLOUD_TOKEN instead." >&2
        exit 64
    fi
    read -rsp "Grafana Cloud access policy token (glc_...): " GLC_TOKEN
    echo >&2
fi
readonly GLC_TOKEN

if [[ "$GLC_TOKEN" != glc_* ]]; then
    echo "error: the token does not start with 'glc_'. Grafana Cloud access policy" >&2
    echo "       tokens always do — check you did not paste a stack URL or a user id." >&2
    exit 64
fi

# The prod backend service is hms-backend-core, NOT hms-backend-prod — only dev follows
# the -<env> pattern. docs/runbooks/railway-env-matrix.md calls this out explicitly; deriving
# the name from the environment would make every prod run fail on a nonexistent service.
case "$ENVIRONMENT" in
    dev)  readonly SERVICE="hms-backend-dev" ;;
    prod) readonly SERVICE="hms-backend-core" ;;
esac

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
echo "  zone           : ${GRAFANA_ZONE}"
echo "  OTLP endpoint  : ${OTLP_ENDPOINT}"
echo "  traces sampler : ${TRACES_SAMPLER}${TRACES_SAMPLER_ARG:+ @ ${TRACES_SAMPLER_ARG}}"
echo

# Exactly one exporter owns each signal:
#   traces + logs -> the OTEL Java agent
#   metrics       -> the Micrometer OTLP registry (application-prod.yml)
# OTEL_METRICS_EXPORTER=none stops the agent also exporting metrics, which would
# duplicate every series and double consumption of the 10k free-tier allowance.
#
# Micrometer's url is a FULL SIGNAL URL, not the base gateway: Boot's own default
# is http://localhost:4318/v1/metrics. Handing it the bare /otlp base makes every
# 60s push a rejected POST, so the business counters never reach Mimir.
public_args=(
    "OTEL_EXPORTER_OTLP_ENDPOINT=${OTLP_ENDPOINT}"
    "OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf"
    "OTEL_METRICS_EXPORTER=none"
    "OTEL_SERVICE_NAME=hms-backend"
    "OTEL_RESOURCE_ATTRIBUTES=deployment.environment=${ENVIRONMENT},service.namespace=hms"
    "OTEL_METRICS_ENABLED=true"
    "OTEL_METRICS_EXPORT_URL=${OTLP_ENDPOINT}/v1/metrics"
    "OTEL_TRACES_SAMPLER=${TRACES_SAMPLER}"
)
[[ -n "$TRACES_SAMPLER_ARG" ]] && public_args+=("OTEL_TRACES_SAMPLER_ARG=${TRACES_SAMPLER_ARG}")

# --skip-deploys keeps this a config-only change; the operator decides when the
# service restarts. Without it, Railway redeploys prod the moment this runs.
railway variable set \
    --service "$SERVICE" \
    --environment "$ENVIRONMENT" \
    --skip-deploys \
    "${public_args[@]}"

# The two credential-bearing variables go in over stdin, so the token is not in
# the railway process's argv either.
printf 'Authorization=Basic %s' "$AUTH_B64" \
    | railway variable set OTEL_EXPORTER_OTLP_HEADERS \
        --stdin --service "$SERVICE" --environment "$ENVIRONMENT" --skip-deploys

printf 'Basic %s' "$AUTH_B64" \
    | railway variable set GRAFANA_OTLP_AUTH_HEADER \
        --stdin --service "$SERVICE" --environment "$ENVIRONMENT" --skip-deploys

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

Then query Grafana Cloud -> Explore -> your Mimir datasource. Note that 'up' is
NOT a useful check here: it is generated by a scraper, and this is a push
pipeline, so it will never exist. Query a metric the app actually emits:

  jvm_memory_used_bytes{deployment_environment="${ENVIRONMENT}"}
  http_server_requests_seconds_count{deployment_environment="${ENVIRONMENT}"}

Traces land in Tempo under service.name = hms-backend; logs in Loki under the
same resource attributes. Give it ~2 minutes: the Micrometer push step is 60s.
NEXT
