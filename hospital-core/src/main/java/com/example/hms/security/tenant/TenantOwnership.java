package com.example.hms.security.tenant;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.RoleValidator;

import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;

/**
 * QA round 5 (D262 class): the check a service WRITE runs after loading a
 * hospital-owned row by id, so a caller acting at hospital B cannot change a
 * row of hospital A by knowing its id.
 *
 * <p>Reads the one scope through {@link ActingScopeResolver#currentScope()},
 * with the semantics of {@code RoleValidator.requireActiveHospitalId()}:
 * <ul>
 *   <li>pinned to a hospital: the row must belong to it, or the caller gets the
 *       SAME 404 as an unknown id (existence elsewhere is not disclosed);</li>
 *   <li>a verified super-admin in global view: allowed;</li>
 *   <li>a patient-only caller: refused, a staff write is never theirs;</li>
 *   <li>no resolvable hospital: refused;</li>
 *   <li>no request at all (a scheduled job): nothing to confine.</li>
 * </ul>
 * Static, so a service adopts it without a new constructor dependency. No
 * generic helpers, per the {@code hospital-core} house rule.
 */
public final class TenantOwnership {

    private TenantOwnership() {
    }

    /**
     * Refuse, as {@code notFoundKey}/{@code id}, a row whose hospital is not
     * the caller's acting hospital.
     *
     * @param owner the row's hospital (a row with none is refused outside global view)
     */
    public static void requireOwnedByActingHospital(Hospital owner, String notFoundKey, Object id) {
        requireOwnedByActingHospital(owner != null ? owner.getId() : null, notFoundKey, id);
    }

    /** {@link #requireOwnedByActingHospital(Hospital, String, Object)} for a hospital id. */
    public static void requireOwnedByActingHospital(UUID ownerHospitalId, String notFoundKey, Object id) {
        UUID acting = actingHospitalIdOrNullForGlobal();
        if (acting != null && !acting.equals(ownerHospitalId)) {
            throw new ResourceNotFoundException(notFoundKey, id);
        }
    }

    /**
     * The hospital this request acts at, or {@code null} for a verified
     * super-admin in global view. Every other scope is refused.
     */
    public static UUID actingHospitalIdOrNullForGlobal() {
        if (HospitalContextHolder.getContext().isEmpty() && RequestContextHolder.getRequestAttributes() == null) {
            // No request at all (a scheduled job, a startup task): there is no
            // caller to confine. Every HTTP request carries a context built by
            // the auth filters, so a request can never take this branch.
            return null;
        }
        ActingScope scope = ActingScopeResolver.currentScope();
        return switch (scope) {
            case ActingScope.Pinned pinned -> pinned.hospitalId();
            case ActingScope.Global global -> null;
            case ActingScope.Refused refused -> throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
            case ActingScope.PatientOwned owned -> throw HospitalScopeRefusedException.patientOwned();
        };
    }
}
