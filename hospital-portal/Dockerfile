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

# Copy custom nginx config as a template
COPY nginx.conf /etc/nginx/nginx.conf.template

# Copy built Angular app
COPY --from=build /app/dist/hospital-portal/browser /usr/share/nginx/html

EXPOSE 80

# At runtime, substitute placeholders in nginx config, then start nginx
CMD sh -c "export PORT=\${PORT:-80} && \
    export API_BASE_URL=\${API_BASE_URL:-http://localhost:8081} && \
    export NGINX_RESOLVER=\${NGINX_RESOLVER:-8.8.8.8} && \
    sed -e \"s|__PORT__|\$PORT|g\" \
        -e \"s|__API_BASE_URL__|\$API_BASE_URL|g\" \
        -e \"s|__NGINX_RESOLVER__|\$NGINX_RESOLVER|g\" \
        /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf && \
    echo 'Starting nginx on port '\$PORT' → API='\$API_BASE_URL && \
    nginx -g 'daemon off;'"
