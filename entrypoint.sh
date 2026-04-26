#!/bin/sh
# Entrypoint script for the HMS backend container (v3).
#
# Problem: When Railway (or Docker) mounts a persistent volume at /app/uploads,
# the mount overlays the directory created during the image build, and the
# mounted directory is owned by root.  The application user (appuser, UID 10001)
# therefore cannot create subdirectories (profile-images/, referral-attachments/,
# etc.) and every file-upload attempt fails with a permission-denied error.
#
# Solution: Run this script as root, ensure the upload directory exists and is
# writable by appuser, then exec the JVM *as appuser* (via su so the JVM process
# itself still runs unprivileged).
#
# We use the absolute path to the JRE binary because the shell spawned by 'su'
# inherits a minimal PATH that does not include /opt/java/openjdk/bin.

set -e

UPLOAD_DIR="${APP_UPLOAD_DIR:-/app/uploads}"
JAVA_BIN="${JAVA_HOME:-/opt/java/openjdk}/bin/java"

echo "[entrypoint] Ensuring upload directory exists: ${UPLOAD_DIR}"
mkdir -p "${UPLOAD_DIR}"
chown -R appuser:appuser "${UPLOAD_DIR}"
chmod 750 "${UPLOAD_DIR}"

echo "[entrypoint] Starting application as appuser..."

# Attach the OpenTelemetry Java Agent when OTEL_EXPORTER_OTLP_ENDPOINT is set.
# This auto-instruments the app to export traces, metrics, and logs to Grafana Cloud.
OTEL_AGENT=""
if [ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ] && [ -f /app/opentelemetry-javaagent.jar ]; then
  OTEL_AGENT="-javaagent:/app/opentelemetry-javaagent.jar"
  echo "[entrypoint] OpenTelemetry agent enabled → ${OTEL_EXPORTER_OTLP_ENDPOINT}"
fi

exec su -s /bin/sh appuser -c "exec ${JAVA_BIN} ${OTEL_AGENT} -Dserver.port=${PORT:-8081} -jar /app/app.jar"
