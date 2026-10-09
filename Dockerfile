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

# JVM memory defaults. The heap is 65% of the container's memory limit, but
# never sized from more than 2 GB of RAM (MaxRAM), so a service left at the
# platform's per-service maximum still gets about a 1.3 GB heap rather than
# 65% of that maximum. Metaspace is capped too; the remainder of the limit is
# for code cache, threads, GC structures and the OpenTelemetry agent.
# -Xlog:gc+init logs the effective heap at startup, so it can be checked.
# To change these for one service, set JDK_JAVA_OPTIONS on it (it replaces this
# default). JAVA_TOOL_OPTIONS is read first, so its -XX flags lose to these
# command-line defaults; only use it for flags not set here. The launcher logs
# a "Picked up JDK_JAVA_OPTIONS" NOTE to stderr at each start; that is expected.
# No ExitOnOutOfMemoryError, by decision: it would turn one oversized request
# into a kill of the whole process, every in-flight request with it.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAM=2g -XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=384m -Xlog:gc+init"

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

exec su -s /bin/sh appuser -c "exec ${JAVA_BIN} ${OTEL_AGENT} -Dserver.port=${PORT} -jar /app/app.jar"
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
