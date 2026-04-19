package com.example.hms.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user / per-IP API rate-limiting filter using Bucket4j token-bucket algorithm.
 * <p>
 * Authenticated requests are rate-limited per username; anonymous requests per IP.
 * When the limit is exceeded the filter returns {@code 429 Too Many Requests} with
 * a {@code Retry-After} header.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code app.rate-limit.requests-per-minute} — default 120</li>
 * </ul>
 *
 * <p><strong>Limitation:</strong> in-memory; not shared across instances.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)  // run after CORS but before security filters
public class RateLimitFilter extends OncePerRequestFilter {

    private final int requestsPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
        log.info("[RATE-LIMIT] Configured at {} requests/minute per key", requestsPerMinute);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String key = resolveKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("[RATE-LIMIT] 429 for key='{}' path={}", key, request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        // Don't rate-limit health checks, actuator, or static assets
        return path.startsWith("/api/actuator")
                || path.startsWith("/assets/")
                || path.startsWith("/static/")
                || path.equals("/api/actuator/health");
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        // Fall back to IP address
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return "ip:" + ip;
    }

    private Bucket createBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
