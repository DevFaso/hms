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

# Create non-root user
RUN useradd -u 10001 -r -s /sbin/nologin appuser

COPY --from=build /app/hospital-core/build/libs/*.jar app.jar

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8081

# Railway injects PORT env var — use shell form so $PORT expands
ENTRYPOINT exec java -Dserver.port=${PORT:-8081} -jar app.jar
