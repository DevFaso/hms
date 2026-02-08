package com.example.hms.security;

import com.example.hms.security.context.HospitalContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Provides the current user identifier for auditing. Prefer values from the {@link HospitalContextHolder}
 * because it is available for async workflows after the security filter executes. Falls back to the Spring
 * Security context via reflective lookup so we do not depend on domain-specific user implementations.
 */
public class SecurityAuditorAware implements AuditorAware<UUID> {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditorAware.class);

    @Override
    public Optional<UUID> getCurrentAuditor() {
        UUID fromTenantContext = HospitalContextHolder.getContext()
            .map(ctx -> ctx.getPrincipalUserId())
            .orElse(null);
        if (fromTenantContext != null) {
            return Optional.of(fromTenantContext);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        UUID extracted = tryExtractUserId(principal);
        return Optional.ofNullable(extracted);
    }

    private UUID tryExtractUserId(Object principal) {
        if (principal == null) {
            return null;
        }

        if (principal instanceof UUID uuid) {
            return uuid;
        }

        if (principal instanceof CharSequence sequence) {
            return parseUuid(sequence.toString());
        }

        try {
            Method method = principal.getClass().getMethod("getUserId");
            Object value = method.invoke(principal);
            if (value instanceof UUID uuid) {
                return uuid;
            }
            if (value instanceof CharSequence sequence) {
                return parseUuid(sequence.toString());
            }
        } catch (ReflectiveOperationException ex) {
            if (log.isTraceEnabled()) {
                log.trace("Principal {} does not expose a user identifier via getUserId()", principal.getClass().getName(), ex);
            }
            // Principal does not expose a user identifier; fall through to empty result.
        }
        return null;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
