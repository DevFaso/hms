package com.example.hms.security.context;

import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Thread-local holder mirroring Spring Security's context strategy for per-request tenant metadata.
 *
 * <p>It also carries the per-request scope state of design §3.4 (the
 * "ActingScopeHolder"): whether a controller has narrowed the scope to a
 * requested hospital, and whether any other consumer (the repository filter,
 * the {@code requireActiveHospitalId} adapter, {@code requirePinned}, the
 * audit hook) has read it — the seal after which the scope may no longer
 * change. Setting or clearing the context resets both, so a request always
 * starts unsealed.
 */
@UtilityClass
public class HospitalContextHolder {

    private static final ThreadLocal<HospitalContext> CONTEXT = new InheritableThreadLocal<>();

    /** Not inheritable: a worker thread spawned mid-request starts unsealed. */
    private static final ThreadLocal<ScopeState> STATE = new ThreadLocal<>();

    public static void setContext(HospitalContext context) {
        STATE.remove();
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
        STATE.remove();
    }

    /**
     * Replace the context with the narrowed one and record that the scope was
     * narrowed. Only {@code ActingScopeResolver.narrowTo} calls this, after
     * checking the seal.
     */
    public static void narrow(HospitalContext narrowed) {
        CONTEXT.set(narrowed);
        state().narrowed = true;
    }

    /** Record that a consumer other than the controller has read the scope. */
    public static void seal() {
        if (CONTEXT.get() != null) {
            state().sealed = true;
        }
    }

    public static boolean isSealed() {
        ScopeState state = STATE.get();
        return state != null && state.sealed;
    }

    public static boolean isNarrowed() {
        ScopeState state = STATE.get();
        return state != null && state.narrowed;
    }

    private static ScopeState state() {
        ScopeState state = STATE.get();
        if (state == null) {
            state = new ScopeState();
            STATE.set(state);
        }
        return state;
    }

    private static final class ScopeState {
        private boolean sealed;
        private boolean narrowed;
    }
}
