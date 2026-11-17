package com.example.hms.exception;

import com.example.hms.security.tenant.ActingScope;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

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

    /**
     * The reason reported for a patient-only caller on an endpoint that asks
     * for one hospital instead of taking it from the record.
     */
    public static final String PATIENT_OWNED = "PATIENT_OWNED";

    private final String reason;

    /**
     * For {@code NO_LONGER_PERMITTED}: the hospital refused, so the portal
     * forgets exactly that one (a stale link to B must not wipe a valid
     * selection A). It is the caller's own former hospital, which they just
     * sent: nothing is disclosed. {@code null} for every other reason.
     */
    private final UUID refusedHospitalId;

    public HospitalScopeRefusedException(ActingScope.Reason reason, String message) {
        this(reason, message, null);
    }

    /** A refused named hospital; the id is kept only for {@code NO_LONGER_PERMITTED}. */
    public HospitalScopeRefusedException(ActingScope.Reason reason, String message, UUID hospitalId) {
        super(message);
        this.reason = reason.name();
        this.refusedHospitalId = reason == ActingScope.Reason.NO_LONGER_PERMITTED ? hospitalId : null;
    }

    /** Global view where one hospital is required. */
    public HospitalScopeRefusedException(String message) {
        super(message);
        this.reason = GLOBAL_VIEW;
        this.refusedHospitalId = null;
    }

    private HospitalScopeRefusedException(String reason, String message) {
        super(message);
        this.reason = reason;
        this.refusedHospitalId = null;
    }

    /** A patient-only caller where the endpoint needs one hospital it did not take from the record. */
    public static HospitalScopeRefusedException patientOwned() {
        return new HospitalScopeRefusedException(PATIENT_OWNED,
            "A patient's request is bounded by their own records, not by a hospital; "
                + "this action needs the hospital of the record it acts on.");
    }

    /** An {@link ActingScope.Reason} name, {@link #GLOBAL_VIEW} or {@link #PATIENT_OWNED}. */
    public String getReason() {
        return reason;
    }

    /** The refused hospital for {@code NO_LONGER_PERMITTED}, else {@code null}. */
    public UUID getRefusedHospitalId() {
        return refusedHospitalId;
    }
}
