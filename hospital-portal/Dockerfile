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

EXPOSE 8080

# At runtime, substitute PORT into nginx config, then start nginx
CMD sh -c "export PORT=\${PORT:-8080} && \
    sed \"s/listen 8080/listen \$PORT/\" /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf && \
    echo 'Starting nginx on port '\$PORT && \
    nginx -g 'daemon off;'"
