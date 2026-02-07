# Multi-stage Dockerfile for Hospital Portal (Angular)
# Build stage
FROM node:20-alpine AS build

WORKDIR /app

# Copy package files first (better layer caching)
COPY package*.json ./

# Install dependencies
RUN npm ci

# Copy source code
COPY . .

# Build for production (default config)
# Override with --build-arg BUILD_CONFIG=uat for UAT builds
ARG BUILD_CONFIG=production
RUN npm run build -- --configuration ${BUILD_CONFIG}

# Runtime stage — nginx to serve static files
FROM nginx:alpine

# Remove default nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Copy custom nginx config
COPY nginx.conf /etc/nginx/nginx.conf

# Copy built Angular app
COPY --from=build /app/dist/hospital-portal/browser /usr/share/nginx/html

EXPOSE 8080

# Railway injects PORT env var — use sed to substitute at container start
# Default to 8080 if PORT not set
CMD sh -c "sed -i 's/listen 8080/listen '\"${PORT:-8080}\"'/' /etc/nginx/nginx.conf && nginx -g 'daemon off;'"
