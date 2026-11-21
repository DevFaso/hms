package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;

import java.util.UUID;

/**
 * Where an imaging role counts: at the hospital it is held at, never token-wide.
 *
 * <p>QA D2915 (r66). A token names the role its user picked at login (or every role they hold),
 * wherever it is held, so an endpoint's gate admits a radiologist of hospital B while they act at
 * hospital A, where they are an imaging technician: they scheduled orders, recorded
 * pre-authorisation numbers, wrote reports and read every report at A. The gate says a caller
 * may be let in; what they are HERE is asked of their assignments at the order's hospital
 * (through {@link RoleValidator}), by every imaging service, with this class:
 * <ul>
 *   <li>who interprets there (a doctor, a physician, a surgeon, a radiologist) schedules, records
 *       the pre-authorisation, writes and signs the report;</li>
 *   <li>the care team there (those, the nurse, the midwife) reads the study in full;</li>
 *   <li>an imaging technician there, and nothing wider there, has the technician's reach (policy
 *       rule 3, owner 2026-11-20, PROVISIONAL), whatever role their token names: a technician at A
 *       who is a radiologist at B is a technician at A;</li>
 *   <li>none of these there: an administrator OF this hospital, or the verified super-admin,
 *       reads as one (user decision 2026-11-14: orders and released reports); anybody else is
 *       refused.</li>
 * </ul>
 * No role, clinical or administrative, is taken from the token: this class never reads it, and
 * {@code ImagingReadRoleGuardTest} holds every imaging service to this class.
 *
 * <p>Stateless: each imaging service builds one over its own {@link RoleValidator}.
 */
public final class ImagingRoleScope {

    static final String TECHNICIAN_ROLE = "IMAGING_TECHNICIAN";
    static final String RADIOLOGIST_ROLE = "RADIOLOGIST";
    static final String NOT_HERE_KEY = "imaging.role.notAtThisHospital";
    static final String ULTRASOUND_KEY = "imaging.technician.ultrasoundPhysiciansOnly";

    /** How a caller reads imaging at a hospital. */
    public enum Reader {
        /** The care team there: every order, every report, every image. */
        CLINICAL,
        /** A technician there: the worklist, the study's images, the released reports of exams they performed. */
        TECHNICIAN,
        /** A technician there who is also an administrator: the technician's images, an administrator's reports. */
        TECHNICIAN_ADMINISTRATOR,
        /** An administrator with no imaging role there: orders and released reports. */
        ADMINISTRATOR;

        public boolean technician() {
            return this == TECHNICIAN || this == TECHNICIAN_ADMINISTRATOR;
        }

        /** Reads reports as an administrator does: the released ones, any exam's. */
        public boolean administrator() {
            return this == ADMINISTRATOR || this == TECHNICIAN_ADMINISTRATOR;
        }
    }

    private final RoleValidator roleValidator;

    public ImagingRoleScope(RoleValidator roleValidator) {
        this.roleValidator = roleValidator;
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /**
     * How the caller reads imaging at this hospital, from the roles they hold there.
     *
     * @throws ClientSafeAccessDeniedException when they hold no imaging role there and are no
     *     administrator: the role that admitted them is held at another hospital
     */
    public Reader readerAt(UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        if (caller != null && hospitalId != null) {
            if (roleValidator.readsImagingClinically(caller, hospitalId)) {
                return Reader.CLINICAL;
            }
            if (roleValidator.hasRole(caller, hospitalId, TECHNICIAN_ROLE)) {
                return administratorAt(caller, hospitalId) ? Reader.TECHNICIAN_ADMINISTRATOR : Reader.TECHNICIAN;
            }
        }
        if (administratorAt(caller, hospitalId)) {
            return Reader.ADMINISTRATOR;
        }
        throw notHere();
    }

    /**
     * Review of #1251: the administrative roles count where they are held too. A hospital admin of
     * B who is a receptionist at A, acting at A under the admin role, read A's worklist, orders,
     * released reports and images on the token alone. An administrator here is:
     * <ul>
     *   <li>a hospital admin or an admin OF this hospital (their assignment there);</li>
     *   <li>the verified super-admin (a live SUPER_ADMIN assignment, never the token's claim), at
     *       the hospital they selected or in global view, as the one tenant resolver says: user
     *       decision 2026-11-14, cross-hospital read of orders and released reports.</li>
     * </ul>
     */
    private boolean administratorAt(UUID caller, UUID hospitalId) {
        if (roleValidator.isSuperAdminActingAt(hospitalId)) {
            return true;
        }
        return caller != null && hospitalId != null && roleValidator.administersImagingReads(caller, hospitalId);
    }

    /** {@link #readerAt} at the order's hospital. */
    public Reader reader(ImagingOrder order) {
        return readerAt(hospitalIdOf(order));
    }

    /**
     * The care team's reads (the pixels, the reading context, a patient's chart list): refused to
     * a technician and to an administrator as to anybody who is not of the care team there.
     */
    public void requireClinicalReaderAt(UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || hospitalId == null || !roleValidator.readsImagingClinically(caller, hospitalId)) {
            throw notHere();
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────

    /**
     * Whether the caller acts on an exam of this hospital as a technician: narrowed to the
     * technician's steps. False for who interprets there (a doctor, a radiologist).
     *
     * @throws ClientSafeAccessDeniedException when they are neither there
     */
    public boolean actsAsTechnicianAt(UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        if (caller != null && hospitalId != null) {
            if (roleValidator.interpretsImaging(caller, hospitalId)) {
                return false;
            }
            if (roleValidator.hasRole(caller, hospitalId, TECHNICIAN_ROLE)) {
                return true;
            }
        }
        throw notHere();
    }

    /** {@link #actsAsTechnicianAt} at the order's hospital. */
    public boolean actsAsTechnician(ImagingOrder order) {
        return actsAsTechnicianAt(hospitalIdOf(order));
    }

    /**
     * The same answer for a screen's flags, never a refusal: the caller is a technician at this
     * hospital and does not interpret there.
     */
    public boolean technicianWriterAt(UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        return caller != null && hospitalId != null && !roleValidator.interpretsImaging(caller, hospitalId)
            && roleValidator.hasRole(caller, hospitalId, TECHNICIAN_ROLE);
    }

    /**
     * The acts of who interprets: the schedule, the pre-authorisation number, the report and its
     * signature, the withdrawal of a signed image. Never the technician's, never a role held at
     * another hospital.
     */
    public void requireInterpreterAt(UUID hospitalId) {
        if (!interpretsAt(hospitalId)) {
            throw notHere();
        }
    }

    /** The same answer with no refusal, for a list that is simply empty for anybody else. */
    public boolean interpretsAt(UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        return caller != null && hospitalId != null && roleValidator.interpretsImaging(caller, hospitalId);
    }

    /** {@link #requireInterpreterAt} at the order's hospital. */
    public void requireInterpreter(ImagingOrder order) {
        requireInterpreterAt(hospitalIdOf(order));
    }

    /** Placing or editing an order: a doctor, a nurse or a midwife of the hospital it is placed at. */
    public void requireOrdererAt(UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || hospitalId == null || !roleValidator.ordersImaging(caller, hospitalId)) {
            throw notHere();
        }
    }

    /** {@link #requireOrdererAt} at the order's hospital. */
    public void requireOrderer(ImagingOrder order) {
        requireOrdererAt(hospitalIdOf(order));
    }

    /** Signing an order: a doctor, a physician or a surgeon of the order's hospital. */
    public void requireSigningPhysician(ImagingOrder order) {
        UUID caller = roleValidator.getCurrentUserId();
        UUID hospitalId = hospitalIdOf(order);
        if (caller == null || hospitalId == null || !roleValidator.isSigningPhysician(caller, hospitalId)) {
            throw notHere();
        }
    }

    /** A radiologist of the order's hospital (the department's own decisions on a study's images). */
    public boolean radiologistAt(ImagingOrder order) {
        UUID caller = roleValidator.getCurrentUserId();
        UUID hospitalId = hospitalIdOf(order);
        return caller != null && hospitalId != null && roleValidator.hasRole(caller, hospitalId, RADIOLOGIST_ROLE);
    }

    // ── The ultrasound setting (owner decision 2026-11-20, provisional) ──

    /**
     * An ultrasound is performed by a physician unless the hospital's setting « échographie par un
     * non-médecin » is on. A caller acting as a technician is refused the exam's steps and its
     * record on an ultrasound order otherwise. Nobody else is concerned.
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

    // ── Internals ────────────────────────────────────────────────────────

    private static ClientSafeAccessDeniedException notHere() {
        return new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(NOT_HERE_KEY));
    }

    /** The order's hospital; an order written before orders carried one is judged where the request acts. */
    private UUID hospitalIdOf(ImagingOrder order) {
        if (order != null && order.getHospital() != null && order.getHospital().getId() != null) {
            return order.getHospital().getId();
        }
        return roleValidator.getCurrentHospitalId();
    }
}
