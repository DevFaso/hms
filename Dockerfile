# Multi-stage Dockerfile for Hospital Management System
# Build stage
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Copy Gradle wrapper and build files first (better layer caching)
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy module build files
COPY hospital-core/build.gradle hospital-core/build.gradle

# Create source directory structure so Gradle resolves
RUN mkdir -p hospital-core/src/main/java

# Download dependencies (cached unless build files change)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

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

# Copy the entrypoint script that fixes volume-mount ownership at startup.
# It runs as root, chowns the uploads directory to appuser, then execs the
# JVM as appuser — so the running process is still unprivileged.
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh \
    && mkdir -p /app/uploads \
    && chown -R appuser:appuser /app

# Run the entrypoint as root so it can chown the (possibly volume-mounted)
# uploads directory, then drop to appuser inside the script.
EXPOSE 8081

ENTRYPOINT ["/entrypoint.sh"]
