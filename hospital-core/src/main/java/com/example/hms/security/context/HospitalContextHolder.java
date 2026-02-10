package com.example.hms.security.context;

import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Thread-local holder mirroring Spring Security's context strategy for per-request tenant metadata.
 */
@UtilityClass
public class HospitalContextHolder {

    private static final ThreadLocal<HospitalContext> CONTEXT = new InheritableThreadLocal<>();

    public static void setContext(HospitalContext context) {
        if (context == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(context);
        }
    }

    public static Optional<HospitalContext> getContext() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static HospitalContext getContextOrEmpty() {
        return CONTEXT.get() != null ? CONTEXT.get() : HospitalContext.empty();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
