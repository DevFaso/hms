package com.example.hms.security.context;

import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Thread-local holder for the request-scoped {@link ImpersonationContext}.
 * Populated by {@code JwtAuthenticationFilter} when the bearer token carries
 * {@code impersonatorUserId} / {@code impersonatorUsername} claims, and
 * cleared in the same {@code finally} block that clears
 * {@link HospitalContextHolder}.
 */
@UtilityClass
public class ImpersonationContextHolder {

    private static final ThreadLocal<ImpersonationContext> CONTEXT = new InheritableThreadLocal<>();

    public static void set(ImpersonationContext context) {
        if (context == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(context);
        }
    }

    public static Optional<ImpersonationContext> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static boolean isImpersonating() {
        return CONTEXT.get() != null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
