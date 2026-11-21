package com.example.hms.service.imaging;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.ImagingModality;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

/**
 * Policy rule 3 (owner 2026-11-20, PROVISIONAL): where the imaging technician role counts, and
 * how far it reaches.
 *
 * <p><b>The role counts at the hospital it is held at.</b> A token carries every role its user
 * holds anywhere, so the endpoints' gates admit a technician of hospital A while they act at
 * hospital B. Every imaging service therefore asks this class, with the hospital of the order,
 * what the caller is THERE (their assignments, through {@link RoleValidator}):
 * <ul>
 *   <li>a doctor or a radiologist there (for an exam write), or any clinical imaging role there
 *       (for a read), keeps that role's reach;</li>
 *   <li>a technician there, and nothing wider there, is narrowed to the technician's reach,
 *       whatever they are at another hospital (a technician at A who is a radiologist at B is
 *       still a technician at A);</li>
 *   <li>neither: a caller the endpoint admits on another role of their token is left as before
 *       (that token-wide pattern for the older roles is standing debt, not this rule's); a caller
 *       it admits only as a technician, a role held elsewhere, is refused.</li>
 * </ul>
 * A token that carries no technician role is not this rule's concern: nothing here applies.
 *
 * <p>Stateless: each imaging service builds one over its own {@link RoleValidator}.
 */
public final class ImagingTechnicianScope {

    static final String ROLE = "IMAGING_TECHNICIAN";
    static final String NOT_HERE_KEY = "imaging.technician.notAtThisHospital";
    static final String ULTRASOUND_KEY = "imaging.technician.ultrasoundPhysiciansOnly";

    private static final Set<String> TECHNICIAN = Set.of(SecurityConstants.ROLE_IMAGING_TECHNICIAN);

    /** The other roles the exam write endpoints admit (ImagingOrderController.EXAM_ROLES and the image writes). */
    private static final Set<String> OTHER_EXAM_ROLES =
        Set.of(SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_RADIOLOGIST);

    /** The other roles the imaging reads admit as staff (the worklist, the images, the reports). */
    private static final Set<String> OTHER_READER_ROLES = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN, SecurityConstants.ROLE_HOSPITAL_ADMIN,
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_NURSE, SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_RADIOLOGIST);

    private final RoleValidator roleValidator;

    public ImagingTechnicianScope(RoleValidator roleValidator) {
        this.roleValidator = roleValidator;
    }

    /**
     * Whether the caller acts on an exam of this hospital as a technician: narrowed to the
     * technician's steps. False for a doctor or a radiologist there, and for a caller whose token
     * carries no technician role.
     *
     * @throws ClientSafeAccessDeniedException when the technician role, held at another hospital,
     *     is all that admitted the caller
     */
    public boolean actsAsTechnicianAt(UUID hospitalId) {
        if (!tokenCarries(TECHNICIAN)) {
            return false;
        }
        if (technicianWriterAt(hospitalId)) {
            return true;
        }
        UUID caller = roleValidator.getCurrentUserId();
        boolean widerHere = caller != null && hospitalId != null && doctorOrRadiologistAt(caller, hospitalId);
        if (widerHere || tokenCarries(OTHER_EXAM_ROLES)) {
            return false;
        }
        throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(NOT_HERE_KEY));
    }

    /**
     * The same answer for a screen's flags, never a refusal: the caller is a technician at this
     * hospital and neither a doctor nor a radiologist there.
     */
    public boolean technicianWriterAt(UUID hospitalId) {
        if (!tokenCarries(TECHNICIAN) || hospitalId == null) {
            return false;
        }
        UUID caller = roleValidator.getCurrentUserId();
        return caller != null && !doctorOrRadiologistAt(caller, hospitalId)
            && roleValidator.hasRole(caller, hospitalId, ROLE);
    }

    private boolean doctorOrRadiologistAt(UUID caller, UUID hospitalId) {
        return physicianAt(caller, hospitalId) || roleValidator.hasRole(caller, hospitalId, "RADIOLOGIST");
    }

    /**
     * Whether the caller reads imaging at this hospital as a technician only: the worklist and the
     * study's images of the hospital, and of the reports the released ones of exams they
     * performed. False for a clinical imaging role held there, and for a caller whose token
     * carries no technician role.
     *
     * @throws ClientSafeAccessDeniedException when the technician role, held at another hospital,
     *     is all that admitted the caller
     */
    public boolean readsAsTechnicianAt(UUID hospitalId) {
        if (!tokenCarries(TECHNICIAN)) {
            return false;
        }
        UUID caller = roleValidator.getCurrentUserId();
        if (caller != null && hospitalId != null) {
            if (clinicalReaderAt(caller, hospitalId)) {
                return false;
            }
            if (roleValidator.hasRole(caller, hospitalId, ROLE)) {
                return true;
            }
        }
        if (tokenCarries(OTHER_READER_ROLES)) {
            return false;
        }
        throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(NOT_HERE_KEY));
    }

    /** {@link #readsAsTechnicianAt} at the order's hospital. */
    public boolean readsAsTechnician(ImagingOrder order) {
        return readsAsTechnicianAt(hospitalIdOf(order));
    }

    /** {@link #actsAsTechnicianAt} at the order's hospital. */
    public boolean actsAsTechnician(ImagingOrder order) {
        return actsAsTechnicianAt(hospitalIdOf(order));
    }

    /**
     * Owner decision 2026-11-20 (provisional): an ultrasound is performed by a physician unless
     * the hospital's setting « échographie par un non-médecin » is on. A caller acting as a
     * technician is refused the exam's steps and its record on an ultrasound order otherwise.
     * Nobody else is concerned.
     */
    public void requireMayPerform(ImagingOrder order) {
        if (!mayPerform(order)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(ULTRASOUND_KEY));
        }
    }

    /** Whether a technician of the order's hospital may perform this study (the setting, for an ultrasound). */
    public static boolean mayPerform(ImagingOrder order) {
        if (order == null || order.getModality() != ImagingModality.ULTRASOUND) {
            return true;
        }
        Hospital hospital = order.getHospital();
        return hospital != null && hospital.isUltrasoundByNonPhysician();
    }

    private boolean physicianAt(UUID caller, UUID hospitalId) {
        return roleValidator.isDoctor(caller, hospitalId) || roleValidator.isPhysician(caller, hospitalId)
            || roleValidator.isSurgeon(caller, hospitalId);
    }

    private boolean clinicalReaderAt(UUID caller, UUID hospitalId) {
        return physicianAt(caller, hospitalId) || roleValidator.isNurse(caller, hospitalId)
            || roleValidator.isMidwife(caller, hospitalId) || roleValidator.hasRole(caller, hospitalId, "RADIOLOGIST");
    }

    private static boolean tokenCarries(Set<String> roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(roles::contains);
    }

    private static UUID hospitalIdOf(ImagingOrder order) {
        return order == null || order.getHospital() == null ? null : order.getHospital().getId();
    }
}
