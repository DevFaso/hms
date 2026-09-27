package com.example.hms.exception;

import com.example.hms.security.tenant.ActingScope;
import org.springframework.security.access.AccessDeniedException;

/**
 * A request asked for a hospital scope it cannot have: it named a hospital the
 * caller may not act at, or it needs one hospital and has none (global view,
 * several hospitals and none named, no hospital at all).
 *
 * <p>Answered 403 with {@link #CODE} and the {@link #getReason() reason}, so a
 * client can tell a stale chip ({@code NO_LONGER_PERMITTED}, which the portal
 * answers by re-reading its scope) from everything else. An
 * {@link AccessDeniedException} so any handler that already treats a refusal as
 * 403 keeps doing so.
 */
public class HospitalScopeRefusedException extends AccessDeniedException {

    /** The error code in the 403 body. */
    public static final String CODE = "hospital_scope_refused";

    /** The reason reported for a super-admin in global view on an endpoint that needs one hospital. */
    public static final String GLOBAL_VIEW = "GLOBAL_VIEW";

    private final String reason;

    public HospitalScopeRefusedException(ActingScope.Reason reason, String message) {
        super(message);
        this.reason = reason.name();
    }

    /** Global view where one hospital is required. */
    public HospitalScopeRefusedException(String message) {
        super(message);
        this.reason = GLOBAL_VIEW;
    }

    /** An {@link ActingScope.Reason} name, or {@link #GLOBAL_VIEW}. */
    public String getReason() {
        return reason;
    }
}
