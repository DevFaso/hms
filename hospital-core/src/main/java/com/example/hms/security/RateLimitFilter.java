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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limiting filter for authentication endpoints using Bucket4j token-bucket algorithm.
 * <p>
 * Only applies to auth-related paths ({@code /api/auth/**}).
 * Authenticated requests are rate-limited per username; anonymous requests per IP.
 * When the limit is exceeded the filter returns {@code 429 Too Many Requests}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code app.rate-limit.requests-per-minute} — default 30 (for auth endpoints)</li>
 *   <li>{@code app.rate-limit.trust-proxy} — default false; set true behind a reverse proxy</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> RATE_LIMITED_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/mfa/verify",
            "/api/auth/password"
    );

    private final int requestsPerMinute;
    private final boolean trustProxy;
    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.rate-limit.requests-per-minute:30}") int requestsPerMinute,
            @Value("${app.rate-limit.trust-proxy:false}") boolean trustProxy) {
        this.requestsPerMinute = requestsPerMinute;
        this.trustProxy = trustProxy;
        log.info("[RATE-LIMIT] Auth endpoints: {} requests/minute, trustProxy={}", requestsPerMinute, trustProxy);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String key = resolveKey(request);
        BucketEntry entry = buckets.computeIfAbsent(key, k -> new BucketEntry(createBucket()));
        entry.lastAccessed = Instant.now();

        if (entry.bucket.tryConsume(1)) {
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
        // Only rate-limit auth endpoints
        return RATE_LIMITED_PREFIXES.stream().noneMatch(path::startsWith);
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        // Only trust X-Forwarded-For when explicitly configured behind a trusted proxy
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return "ip:" + forwarded.split(",")[0].trim();
            }
        }
        return "ip:" + request.getRemoteAddr();
    }

    private Bucket createBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /** Evict stale bucket entries every 5 minutes to prevent unbounded memory growth. */
    @Scheduled(fixedRate = 300_000)
    public void evictStaleBuckets() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessed.isBefore(cutoff));
        int evicted = before - buckets.size();
        if (evicted > 0) {
            log.debug("[RATE-LIMIT] Evicted {} stale bucket entries", evicted);
        }
    }

    private static class BucketEntry {
        final Bucket bucket;
        volatile Instant lastAccessed;

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccessed = Instant.now();
        }
    }
}
