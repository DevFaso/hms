# syntax=docker/dockerfile:1
# Multi-stage Dockerfile for Hospital Management System
# Build stage
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Copy Gradle wrapper and build files first (better layer caching)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
# gradle.properties carries org.gradle.jvmargs — without it the forked
# single-use daemon runs on the 512 MiB default heap, which compileJava
# outgrew (Railway builder GC-thrash kill, 2026-09-04).
COPY gradle.properties .

# Copy module build files
COPY hospital-core/build.gradle hospital-core/build.gradle

# Create source directory structure so Gradle resolves
RUN mkdir -p hospital-core/src/main/java

# Fix CRLF → LF (Windows checkouts) and make executable
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy actual source code
COPY hospital-core/src hospital-core/src

# Build the application
RUN ./gradlew :hospital-core:bootJar -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create non-root user that the JVM will run as after the entrypoint drops
# privileges.  UID 10001 is used to avoid collisions with well-known system UIDs.
RUN useradd -u 10001 -r -s /sbin/nologin appuser

COPY --from=build /app/hospital-core/build/libs/*.jar app.jar

# Download OpenTelemetry Java Agent for Grafana Cloud observability (traces, metrics, logs).
# The agent auto-instruments the application at startup — no code changes needed.
# Activated only when OTEL_EXPORTER_OTLP_ENDPOINT is set (Railway env vars).
ADD --chmod=444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.12.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# Inline the entrypoint script (BuildKit heredoc) so the build has zero
# dependency on entrypoint.sh existing in the build context. This previously
# bit Railway when its cached build snapshot diverged from the freshly
# uploaded one and the COPY layer failed with `entrypoint.sh: not found`
# even though the file was on the branch (see commit a4b68f12 history).
# Single source of truth — no on-disk entrypoint.sh to drift against.
#
# v3 — runs as root to chown the (possibly volume-mounted) uploads directory,
# then execs the JVM as appuser. Conditionally attaches the OTEL Java agent
# when OTEL_EXPORTER_OTLP_ENDPOINT is set.
COPY <<'ENTRYPOINT_SH' /entrypoint.sh
#!/bin/sh
# Entrypoint script for the HMS backend container (v3 — inlined into Dockerfile).
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

# Validate PORT: must be a non-empty string of digits. Anything else (empty,
# whitespace, shell metacharacters) falls back to 8081 — this prevents an
# attacker-controlled PORT env var from injecting extra JVM args via the
# `su -c` command string below. Railway always supplies a numeric PORT, so
# the fallback path is just defense-in-depth.
PORT="${PORT:-8081}"
case "${PORT}" in
  ''|*[!0-9]*)
    echo "[entrypoint] Invalid PORT='${PORT}' (not a positive integer); falling back to 8081" >&2
    PORT=8081
    ;;
esac

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

# --- JVM memory: the whole process inside this container's limit --------------
# The kernel kills the container when heap PLUS native memory passes the limit,
# and no Java code can catch that. QA D1314: at MaxRAMPercentage=70 with nothing
# else bounded, heap 2.8 GB + an unbounded metaspace + direct memory (default:
# as much as the heap) + code cache + stacks + GC tables could pass 4 GB, and
# did: "Killed", then CRASHED, with no OutOfMemoryError in the log. With the
# native parts bounded, a spike ends in an OutOfMemoryError that fails that one
# request, which is the decision below. The sum, per limit:
# docs/runbooks/railway-env-matrix.md §3a; JvmContainerBudgetGuardTest checks it.
#
# Heap = JVM_HEAP_PERCENT (clamped to 10-90) of the container's memory limit,
# which the JVM reads from cgroups. Default 60; 50 under a 3 GB limit, where the
# fixed native bounds weigh more. The service's memory limit is the control:
# every service must have one, or the JVM sizes from the platform's per-service
# maximum (docs/runbooks/railway-env-matrix.md §3a). -Xlog:gc+init logs the
# effective heap at startup. An -Xmx in JAVA_TOOL_OPTIONS overrides this; a
# MaxRAMPercentage there loses to it. HEAP_PCT is reduced to digits before it
# reaches the `su -c` string below.
# No ExitOnOutOfMemoryError, by decision: one oversized request should fail
# that request, not kill the whole process.
LIMIT_MB=0
for LIMIT_FILE in /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory/memory.limit_in_bytes; do
  if [ -r "${LIMIT_FILE}" ]; then
    LIMIT_BYTES="$(cat "${LIMIT_FILE}")"
    case "${LIMIT_BYTES}" in
      ''|*[!0-9]*) ;;
      *) LIMIT_MB=$((LIMIT_BYTES / 1048576)); break ;;
    esac
  fi
done
DEFAULT_HEAP_PCT=60
if [ "${LIMIT_MB}" -gt 0 ] && [ "${LIMIT_MB}" -lt 3072 ]; then DEFAULT_HEAP_PCT=50; fi
HEAP_PCT="${JVM_HEAP_PERCENT:-${DEFAULT_HEAP_PCT}}"
case "${HEAP_PCT}" in
  [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) ;;
  *) echo "[entrypoint] Invalid JVM_HEAP_PERCENT='${HEAP_PCT}'; using ${DEFAULT_HEAP_PCT}" >&2; HEAP_PCT="${DEFAULT_HEAP_PCT}" ;;
esac
if [ "${HEAP_PCT}" -lt 10 ]; then HEAP_PCT=10; fi
if [ "${HEAP_PCT}" -gt 90 ]; then HEAP_PCT=90; fi
JVM_NATIVE_BOUNDS="-XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=192m -XX:MaxDirectMemorySize=128m -Xss768k"
# glibc keeps one malloc arena per thread by default: native memory nothing above bounds.
export MALLOC_ARENA_MAX=2
JVM_MEMORY_OPTS="-XX:MaxRAMPercentage=${HEAP_PCT} ${JVM_NATIVE_BOUNDS} -Xlog:gc+init"

exec su -s /bin/sh appuser -c "exec ${JAVA_BIN} ${JVM_MEMORY_OPTS} ${OTEL_AGENT} -Dserver.port=${PORT} -jar /app/app.jar"
ENTRYPOINT_SH

# Strip CRLF from the heredoc'd script in case the Dockerfile was checked out
# with Windows line endings (.gitattributes enforces LF for *.sh and Dockerfile,
# but this is a cheap belt-and-suspenders against a misconfigured local checkout).
RUN sed -i 's/\r$//' /entrypoint.sh \
    && chmod +x /entrypoint.sh \
    && mkdir -p /app/uploads \
    && chown -R appuser:appuser /app

# Run the entrypoint as root so it can chown the (possibly volume-mounted)
# uploads directory, then drop to appuser inside the script.
EXPOSE 8081

ENTRYPOINT ["/entrypoint.sh"]
