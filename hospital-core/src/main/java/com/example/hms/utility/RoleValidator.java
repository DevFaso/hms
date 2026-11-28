package com.example.hms.utility;

import com.example.hms.config.SecurityConstants;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.PrincipalUserIds;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RoleValidator {
    private static final String HOSPITAL_ADMIN_ROLE = "HOSPITAL_ADMIN";
    private static final String DOCTOR_ROLE = "DOCTOR";
    private static final String PHYSICIAN_ROLE = "PHYSICIAN";
    private static final String SURGEON_ROLE = "SURGEON";
    private static final String NURSE_ROLE = "NURSE";
    private static final String MIDWIFE_ROLE = "MIDWIFE";
    private static final String RADIOLOGIST_ROLE = "RADIOLOGIST";
    private static final String ACCOUNTANT_ROLE = "ACCOUNTANT";
    private static final String PHARMACIST_ROLE = "PHARMACIST";
    private static final String BILLING_SPECIALIST_ROLE = "BILLING_SPECIALIST";
    private static final String RECEPTIONIST_ROLE = "RECEPTIONIST";
    private static final String CLAIMS_REVIEWER_ROLE = "CLAIMS_REVIEWER";
    private static final String ROLE_PREFIX = SecurityConstants.ROLE_PREFIX;

    /**
     * What a caller with no resolvable hospital is told. Public so a guard
     * that refuses the same condition for its own reason — the encounter
     * reads refuse a {@code null} scope the verified super-admin flag does not
     * back — says it in the same words, and a rewording here reaches both.
     */
    public static final String HOSPITAL_CONTEXT_REQUIRED =
        "Hospital context required. Please select an active hospital or include X-Hospital-Id header.";


    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ActingScopeResolver actingScopeResolver;

    /* =========================================
       Authority helpers (JWT/global authorities)
       ========================================= */
    private Set<String> expandCodes(String base) {
        String u = base == null ? "" : base.toUpperCase();
        // Support both ROLE_* and bare forms
        return Set.of(u, u.startsWith(ROLE_PREFIX) ? u.substring(ROLE_PREFIX.length()) : ROLE_PREFIX + u);
    }

    public boolean hasAnyAuthority(String... bases) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        var wanted = new HashSet<String>();
        for (var b : bases) wanted.addAll(expandCodes(b));
        return auth.getAuthorities().stream()
            .map(a -> a.getAuthority().toUpperCase())
            .anyMatch(wanted::contains);
    }

    private boolean hasAuthority(String base) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        var wanted = expandCodes(base);
        return auth.getAuthorities().stream()
            .map(a -> a.getAuthority().toUpperCase())
            .anyMatch(wanted::contains);
    }

    /**
     * The same answer as {@link #isSuperAdminFromJwtClaim()}: the live,
     * verified signal ({@link ActingScopeResolver#isVerifiedSuperAdmin()}).
     * It used to read the authorities collection, which a stale token, a
     * demoted super-admin or a Keycloak realm role could carry; its callers
     * now agree with every other scope decision, so it is kept as a correct
     * adapter for them rather than deprecated.
     */
    public boolean isSuperAdminFromAuth() { return isSuperAdminFromJwtClaim(); }

    /**
     * The verified super-admin signal for scope decisions: the caller holds a
     * live, active SUPER_ADMIN assignment, read from the assignment table on
     * this request by the shared computation both auth filters use
     * ({@link ActingScopeResolver#liveContext}). It is NOT the token's
     * {@code isSuperAdmin} claim and NOT the authorities collection — the name
     * is historical: the claim it once read was itself derived from the
     * authorities, and on Keycloak the authority was the only input (design
     * D5). A demoted super-admin loses it on the next request.
     */
    public boolean isSuperAdminFromJwtClaim() {
        return actingScopeResolver.isVerifiedSuperAdmin();
    }

    public boolean isHospitalAdminFromAuthGlobalOnly() { return hasAuthority(HOSPITAL_ADMIN_ROLE); }
    public boolean isPatientFromAuth() { return hasAuthority("PATIENT"); }

    /** True iff the caller has PATIENT role and does NOT have any staff/admin role */
    public boolean isPatientOnlyFromAuth() {
        boolean isPatient = hasAnyAuthority("PATIENT");
        boolean isStaffOrAdmin = isStaffOrAdminFromAuth();
        return isPatient && !isStaffOrAdmin;
    }

    /** Quick check for “can act as staff/admin” */
    public boolean isStaffOrAdminFromAuth() {
        return hasAnyAuthority(HOSPITAL_ADMIN_ROLE, DOCTOR_ROLE, PHYSICIAN_ROLE, NURSE_ROLE, MIDWIFE_ROLE,"STAFF","RECEPTIONIST","SUPER_ADMIN");
    }

    /* =========================================
       Current principal helpers
       ========================================= */
    /**
     * The caller's local user id: a password-path principal's, or a Keycloak
     * principal's {@code appUserId} claim ({@link PrincipalUserIds}). Null
     * when there is none.
     */
    public UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.example.hms.model.User u) {
            return u.getId();
        }
        return PrincipalUserIds.of(auth).orElse(null);
    }

    /**
     * The hospital this request acts at, or {@code null} for global view or no
     * hospital. Once its own "exactly one active assignment" lookup; now the
     * resolver's answer, whose {@code SOLE_ASSIGNMENT} rule subsumes it.
     */
    public UUID getCurrentHospitalId() {
        return actingScopeResolver.current() instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
    }

    /**
     * The hospital this request acts at, from the one tenant resolver
     * ({@link ActingScopeResolver}, docs/security/tenant-resolution.md). This
     * is the thin adapter every hospital-scoped service calls:
     * <ul>
     *   <li>{@code Pinned} → its hospital: the one the caller named
     *       ({@code X-Hospital-Id} or a controller's {@code narrowTo}), else
     *       the only one they hold;</li>
     *   <li>{@code Global} → {@code null}: a VERIFIED super-admin (a live
     *       SUPER_ADMIN assignment) who named no hospital. Nothing else can
     *       reach {@code null}; the old authorities-based fallback ("step 4")
     *       is gone;</li>
     *   <li>{@code Refused} (several hospitals and none named, none held, no
     *       local account) → {@link BusinessException} with
     *       {@link #HOSPITAL_CONTEXT_REQUIRED}.</li>
     * </ul>
     * Reading the scope seals it for the rest of the request.
     */
    public UUID requireActiveHospitalId() {
        ActingScope scope = actingScopeResolver.current();
        return switch (scope) {
            case ActingScope.Pinned pinned -> pinned.hospitalId();
            case ActingScope.Global global -> null;
            case ActingScope.Refused refused -> throw new BusinessException(HOSPITAL_CONTEXT_REQUIRED);
            // Never null for a patient: null means an unscoped super-admin to
            // every caller of this method. A patient-reached path takes its
            // hospital from the record (design Q1).
            case ActingScope.PatientOwned owned -> throw HospitalScopeRefusedException.patientOwned();
        };
    }

    /** Active assignment for (currentUser, currentHospital) if uniquely determined */
    public UserRoleHospitalAssignment getCurrentAssignmentForHospital() {
        UUID uid = getCurrentUserId();
        UUID hid = getCurrentHospitalId();
        if (uid == null || hid == null) return null;
        return assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(uid, hid).orElse(null);
    }

    /* =========================================
       Hospital‑scoped role checks (DB-backed)
       ========================================= */
    private Set<String> expandCodesForDb(String base) {
        // Reuse expandCodes but DB layer may accept both forms
        return expandCodes(base);
    }

    private boolean hasAnyCode(UUID userId, UUID hospitalId, String baseCode) {
        return assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            userId, hospitalId, expandCodesForDb(baseCode));
    }

    /** Any of several base codes, in ONE query (the repository takes the whole set). */
    private boolean hasAnyCode(UUID userId, UUID hospitalId, String... baseCodes) {
        var wanted = new HashSet<String>();
        for (var base : baseCodes) wanted.addAll(expandCodesForDb(base));
        return assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(userId, hospitalId, wanted);
    }

    /**
     * QA D934: a doctor, physician or surgeon at {@code hospitalId} - the roles
     * whose signature a prescription carries. One query over the three codes.
     */
    public boolean isSigningPhysician(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE);
    }

    /**
     * QA D934: a nurse or a midwife at {@code hospitalId} who is not also a
     * signing physician there: her draft waits for a doctor of that hospital
     * to sign. Deliberately NOT "anyone who is not a physician" (review of
     * #975): a doctor credentialed elsewhere, or a prescriber outside these
     * two roles, keeps "only the prescribing clinician signs".
     */
    public boolean draftsForPhysicianSignature(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, NURSE_ROLE, MIDWIFE_ROLE) && !isSigningPhysician(userId, hospitalId);
    }

    /**
     * Product decision 2026-10-28 ("as it works in real hospitals"): the
     * licensed-physician roles this codebase seeds (V2__seed_roles.sql) - the
     * doctor (general practitioner), the physician, and the specialist
     * physicians: surgeon, anaesthesiologist, radiologist. Nurses, midwives,
     * receptionists and administrators are not physicians. The medical
     * certificate endpoint's {@code @PreAuthorize} names exactly these
     * (MedicalCertificateServiceImplTest pins the two lists together).
     */
    public static final java.util.List<String> LICENSED_PHYSICIAN_ROLE_CODES = java.util.List.of(
        DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, "ANESTHESIOLOGIST", RADIOLOGIST_ROLE);

    /**
     * QA D1164 (round 21): the roles a visit takes as its provider - every
     * licensed physician, the nurse and the midwife (a midwife runs her own
     * antenatal and delivery visits), and the hospital administrator the old
     * check admitted. The walk-in refused the midwife the appointment check-in
     * accepted, and a physician or a surgeon too, because each check listed
     * roles by hand; this is the one list.
     */
    public static final java.util.List<String> ENCOUNTER_PROVIDER_ROLE_CODES;

    static {
        java.util.List<String> codes = new java.util.ArrayList<>(LICENSED_PHYSICIAN_ROLE_CODES);
        codes.addAll(java.util.List.of(NURSE_ROLE, MIDWIFE_ROLE, HOSPITAL_ADMIN_ROLE));
        ENCOUNTER_PROVIDER_ROLE_CODES = java.util.List.copyOf(codes);
    }

    /** A role a visit takes as its provider ({@link #ENCOUNTER_PROVIDER_ROLE_CODES}), in one query. */
    public boolean isEncounterProvider(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, ENCOUNTER_PROVIDER_ROLE_CODES.toArray(new String[0]));
    }

    /** A licensed physician ({@link #LICENSED_PHYSICIAN_ROLE_CODES}) at {@code hospitalId}, in one query. */
    public boolean isLicensedPhysician(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, LICENSED_PHYSICIAN_ROLE_CODES.toArray(new String[0]));
    }

    public boolean isDoctor(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, DOCTOR_ROLE); }
    public boolean isPhysician(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, PHYSICIAN_ROLE); }
    public boolean isSurgeon(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, SURGEON_ROLE); }
    public boolean isNurse(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, NURSE_ROLE); }
    public boolean isMidwife(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, MIDWIFE_ROLE); }
    /** One query: a clinician or a pharmacist at {@code hospitalId}, not the union of the caller's assignments. */
    public boolean isClinicianOrPharmacist(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, NURSE_ROLE, MIDWIFE_ROLE, PHARMACIST_ROLE);
    }
    public boolean isHospitalAdmin(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE); }
    public boolean isLabScientist(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_SCIENTIST"); }
    public boolean isLabTechnician(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_TECHNICIAN"); }
    public boolean isLabManager(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_MANAGER"); }
    public boolean isLabDirector(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_DIRECTOR"); }
    /** True if the user has any lab staff role (scientist, technician, or manager) in the given hospital. */
    public boolean isLabStaff(UUID userId, UUID hospitalId) {
        return isLabScientist(userId, hospitalId)
            || isLabTechnician(userId, hospitalId)
            || isLabManager(userId, hospitalId);
    }
    public boolean hasRole(UUID userId, UUID hospitalId, String roleCode) { return hasAnyCode(userId, hospitalId, roleCode); }

    /**
     * QA D2915 (r66): the imaging roles, each read AT {@code hospitalId} in one query. A token names
     * the role its user picked at login, wherever it is held; an imaging service asks these with the
     * order's hospital instead ({@code ImagingRoleScope}).
     *
     * <p>Who interprets a study and decides for the imaging department there: a doctor, a physician,
     * a surgeon or a radiologist.
     */
    public boolean interpretsImaging(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, RADIOLOGIST_ROLE);
    }

    /** QA D2915: the care team that reads a study in full there: who interprets it, the nurse and the midwife. */
    public boolean readsImagingClinically(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, RADIOLOGIST_ROLE,
            NURSE_ROLE, MIDWIFE_ROLE);
    }

    /**
     * Review of #1251: who reads imaging there as an administrator (orders and released reports,
     * user decision 2026-11-14): a hospital admin or an admin OF that hospital. A hospital admin of
     * another hospital is not one here; the verified super-admin is asked separately.
     */
    public boolean administersImagingReads(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, "ADMIN");
    }

    /**
     * Review of #1251: the verified super-admin (a live SUPER_ADMIN assignment) where they act: in
     * global view, or at the very hospital they selected. Asked of the one tenant resolver
     * ({@code CallerIdentity.isSuperAdminFor}); never the token's authority.
     */
    public boolean isSuperAdminActingAt(UUID hospitalId) {
        return com.example.hms.security.CallerIdentity.isSuperAdminFor(hospitalId);
    }

    /**
     * QA D4270 review: the staff who collect any approved release-of-information copy of {@code hospitalId}: a
     * doctor, a nurse or a midwife assigned THERE. Reception is not one (it reads no clinical content), and a role
     * held at another hospital does not count.
     */
    public boolean collectsReleaseCopies(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, DOCTOR_ROLE, NURSE_ROLE, MIDWIFE_ROLE);
    }

    /** QA D2915: who places an imaging order there: a doctor, a physician, a surgeon, a nurse or a midwife. */
    public boolean ordersImaging(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, NURSE_ROLE, MIDWIFE_ROLE);
    }

    /**
     * Billing-money plan PR 2 (owner decision D-1, 2026-11-15): who maintains a hospital's price list - a hospital
     * admin or an accountant assignment AT that hospital. Never the cashier, a clinician or the platform super-admin
     * on their own (RoleExpansion grants the super-admin the hospital admin AUTHORITY, never an assignment).
     */
    public boolean canMaintainPriceList(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE);
    }

    /**
     * Billing-money plan PR 3 (owner decision D-2, plan section 3.8): who may override a tariff price on a draft line,
     * with a reason - a billing specialist (the cashier) or a hospital admin assignment AT that hospital. Never the
     * platform super-admin on their own, never the accountant (who sets prices, D-1).
     */
    public boolean canOverrideTariff(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, BILLING_SPECIALIST_ROLE);
    }

    /**
     * Billing-money plan PR 8 (plan section 3.8): who may ask for a refund at {@code hospitalId}: a hospital admin, an
     * accountant, a billing specialist or a receptionist (the copay till) assignment there. Never the platform
     * super-admin on their own.
     */
    public boolean canRequestRefund(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE,
            RECEPTIONIST_ROLE);
    }

    /**
     * Billing-money plan PR 8 (owner decision D-3): who may approve or reject a refund at {@code hospitalId}: a
     * hospital admin or an accountant assignment there. The service also refuses the person who asked for it.
     */
    public boolean canApproveRefund(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE);
    }

    /** Billing-money plan PR 8 (plan section 3.8): who hands an approved refund to the patient at a till. */
    public boolean canPayRefund(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, BILLING_SPECIALIST_ROLE, RECEPTIONIST_ROLE);
    }

    /**
     * Policy rule 6 (provisional; QA D2675): who may hand a fill over unpaid on a logged « urgence » override, with a
     * reason that finance reviews afterwards: a pharmacist or a hospital admin assignment at {@code hospitalId}
     * (never a verifier or the super-admin alone). This is the ONLY way the counter lets an unpaid fill go on its
     * own word; every other deferral is approved by somebody else first.
     */
    public boolean canOverrideDispenseBillingForUrgency(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, PHARMACIST_ROLE, HOSPITAL_ADMIN_ROLE);
    }

    /**
     * Policy rule 6: who may ASK for a fill to leave « à facturer » at {@code hospitalId}: the counter or the money
     * desks (a pharmacist, a billing specialist, an accountant or a hospital admin). Asking decides nothing. Never
     * reception: the request names a medicine, which the front desk does not read.
     */
    public boolean canRequestDispenseBillingDeferral(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, PHARMACIST_ROLE, BILLING_SPECIALIST_ROLE, ACCOUNTANT_ROLE,
            HOSPITAL_ADMIN_ROLE);
    }

    /**
     * Policy rule 6: who checks a third-party cover against its claim or voucher at {@code hospitalId}: billing
     * (a billing specialist), finance (an accountant) or a hospital admin. Never the pharmacist.
     */
    public boolean canApproveThirdPartyCover(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, BILLING_SPECIALIST_ROLE, ACCOUNTANT_ROLE, HOSPITAL_ADMIN_ROLE);
    }

    /**
     * Policy rule 6: who grants an outpatient credit at {@code hospitalId}: finance (an accountant) or a hospital
     * admin. Never the pharmacist, never billing alone. The social service has no role in the product yet.
     */
    public boolean canApproveOutpatientCredit(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, ACCOUNTANT_ROLE, HOSPITAL_ADMIN_ROLE);
    }

    /**
     * Policy rule 6 (review of #1244): who stands behind a claim as cover of a fill at {@code hospitalId}: a money
     * desk (billing specialist, claims reviewer, accountant) or a hospital admin. A claim only a pharmacist sent
     * or recorded covers nothing at the counter.
     */
    public boolean vouchesForClaimCover(UUID userId, UUID hospitalId) {
        return userId != null && hospitalId != null
            && hasAnyCode(userId, hospitalId, BILLING_SPECIALIST_ROLE, CLAIMS_REVIEWER_ROLE, ACCOUNTANT_ROLE,
                HOSPITAL_ADMIN_ROLE);
    }

    /**
     * QA D2925 (r66; owner's delegate 2026-11-20): who records the PAYER's decision on a pharmacy claim (accepted,
     * rejected, paid) at {@code hospitalId}: a money desk (billing specialist, claims reviewer, accountant) or a
     * hospital admin. The pharmacy creates and sends a claim; it never records the answer to its own claim. A
     * pharmacist who "accepted" a claim left it ACCEPTED with nobody behind it, and billing could no longer accept
     * it: the fill stayed blocked until the payer's payment, which can take weeks.
     */
    public boolean canRecordPayerDecision(UUID userId, UUID hospitalId) {
        return userId != null && hospitalId != null
            && hasAnyCode(userId, hospitalId, BILLING_SPECIALIST_ROLE, CLAIMS_REVIEWER_ROLE, ACCOUNTANT_ROLE,
                HOSPITAL_ADMIN_ROLE);
    }

    /**
     * Policy rule 6: who reads what a fill may leave on before a hand-over: whoever may ask, and the pharmacy
     * verifier, who hands fills over too (and may execute an approved deferral, never override).
     */
    public boolean canReadDispenseBillingCounter(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, PHARMACIST_ROLE, "PHARMACY_VERIFIER", BILLING_SPECIALIST_ROLE,
            ACCOUNTANT_ROLE, HOSPITAL_ADMIN_ROLE);
    }

    /** Policy rule 6: who reviews an urgency override afterwards: an accountant, a billing specialist or a hospital admin. */
    public boolean canReviewDispenseBillingOverride(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE, HOSPITAL_ADMIN_ROLE);
    }

    /** Policy rules 1 and 6: who sets a hospital's billing policy: a hospital admin assignment there. */
    public boolean canSetHospitalBillingPolicy(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE);
    }

    /**
     * Policy rule 1 (provisional; Code de la santé publique art. 276, QA D2308): who may approve the renewal of a
     * prescription at {@code hospitalId}. A physician (doctor, physician or surgeon) for every list; a nurse or a
     * midwife for Lists I and II only, which the catalogue knows as "not controlled". Never a pharmacist, whatever
     * else the request says. Narcotics and controlled drugs are not renewed by anybody: they need a new
     * prescription, which the renewal itself refuses. The product has no dentist role yet; a dentist signs as a doctor.
     */
    public boolean canApproveRenewal(UUID userId, UUID hospitalId, boolean controlled) {
        if (userId == null || hospitalId == null) {
            return false;
        }
        if (isSigningPhysician(userId, hospitalId)) {
            return true;
        }
        return !controlled && hasAnyCode(userId, hospitalId, NURSE_ROLE, MIDWIFE_ROLE);
    }

    /**
     * V356: who reads a hospital's provisional rules and their sign-off status (the « Règles cliniques et
     * validation » list, the « provisoire » labels, the sign-off pack): a role held AT {@code hospitalId} that
     * sees a provisional rule. A role held at another hospital, or a patient's registration here, opens nothing.
     */
    public boolean canReadClinicalRules(UUID userId, UUID hospitalId) {
        return userId != null && hospitalId != null && hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE,
            DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, NURSE_ROLE, MIDWIFE_ROLE, PHARMACIST_ROLE, RADIOLOGIST_ROLE,
            "IMAGING_TECHNICIAN", "LAB_DIRECTOR", "QUALITY_MANAGER", BILLING_SPECIALIST_ROLE, ACCOUNTANT_ROLE);
    }

    /** Policy rule 1: who reviews, flags or refuses a renewal request at the pharmacy: a pharmacist there. */
    public boolean canReviewRenewalAtPharmacy(UUID userId, UUID hospitalId) {
        return userId != null && hospitalId != null && hasAnyCode(userId, hospitalId, PHARMACIST_ROLE);
    }

    /**
     * Review of #1239: who sets or changes a pharmacy's selling price there - the store manager or a hospital admin,
     * on an assignment AT that hospital. Never the pharmacist or the inventory clerk who also sells at that price.
     */
    public boolean canSetPharmacyPrice(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, "STORE_MANAGER", HOSPITAL_ADMIN_ROLE);
    }

    /** QA D2676 (r65, billing plan PR 13): who may ask for a pharmacy refund of a patient return there. */
    public boolean canRequestPharmacyRefund(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, PHARMACIST_ROLE, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE,
            BILLING_SPECIALIST_ROLE);
    }

    /** QA D2676: who hands an approved pharmacy refund to the patient. */
    public boolean canPayPharmacyRefund(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, PHARMACIST_ROLE, HOSPITAL_ADMIN_ROLE, BILLING_SPECIALIST_ROLE);
    }

    /** QA D2673 (r65): who reads a pharmacy's daily reconciliation there. */
    public boolean canReadPharmacyReconciliation(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, PHARMACIST_ROLE, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE);
    }

    /**
     * Billing-money plan PR 10 (plan section 3.8): who supervises the hospital's tills: sees every cash session of
     * the day, reopens a closed one with a reason, marks one reviewed. A hospital admin or an accountant there.
     */
    public boolean canSuperviseTills(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE);
    }

    /**
     * PR 10 review: who holds a till at {@code hospitalId}, the people who can count and close their own cash
     * session there: a hospital admin, an accountant, a billing specialist, a receptionist or a pharmacist
     * assignment. Money taken by anyone else (the platform super-admin on their expanded authorities) would sit in
     * a session nobody can close.
     */
    public boolean canHoldTill(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE,
            RECEPTIONIST_ROLE, "PHARMACIST");
    }

    /** Billing-money plan PR 10: whether the refund payer hands money back at the billing desk (else reception). */
    public boolean paysAtBillingDesk(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, BILLING_SPECIALIST_ROLE, ACCOUNTANT_ROLE);
    }

    /** Billing-money plan PR 2 (plan section 3.8): who reads a hospital's price list there. */
    public boolean canReadPriceList(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE,
            RECEPTIONIST_ROLE, CLAIMS_REVIEWER_ROLE);
    }

    /**
     * Billing-money plan PR 6 (owner decision D-1, 2026-11-15): who maintains a hospital's payer catalogue (payers,
     * plans and their rules) - a hospital admin or an accountant assignment AT that hospital, as for the price list.
     * Never the cashier, a clinician or the platform super-admin on their own.
     */
    public boolean canMaintainPayers(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE);
    }

    /**
     * Billing-money plan PR 7 (plan section 3.8, "Payer share bordereau" row): who marks a payer's share of an
     * invoice claimed, paid or rejected at {@code hospitalId}: a hospital admin, an accountant, a billing specialist
     * or a claims reviewer assignment there.
     */
    public boolean canDecidePayerShare(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE,
            CLAIMS_REVIEWER_ROLE);
    }

    /**
     * Insurer claims plan PR 3 (owner decision of 2026-11-26, Q2): who records, allocates or reverses a payer's
     * remittance at {@code hospitalId}, cancels a submitted bordereau, re-bills a refused amount to the patient and
     * approves or refuses a write-off: a hospital admin or an accountant assignment there. Never the billing
     * specialist (the till) and never the platform super-admin on their own.
     */
    public boolean canSettleInsurerClaims(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE);
    }

    /**
     * Insurer claims plan PR 1 (QA D3984, plan section 4.4): who records or corrects the insurer's pre-authorisation
     * number of an act at {@code hospitalId} - on its invoice line or, without any clinical field, on its imaging
     * order: the deciders of a payer share and reception, which obtains the number from the patient's insurer.
     */
    public boolean canRecordPreAuthorisation(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE,
            CLAIMS_REVIEWER_ROLE, RECEPTIONIST_ROLE);
    }

    /** Billing-money plan PR 6: who reads a hospital's payer catalogue there (the price list's readers). */
    public boolean canReadPayers(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, ACCOUNTANT_ROLE, BILLING_SPECIALIST_ROLE,
            RECEPTIONIST_ROLE, CLAIMS_REVIEWER_ROLE);
    }

    /**
     * Billing-money plan PR 6 (plan section 3.8): who links a patient's coverage to a payer plan at that hospital -
     * the hospital admin, the billing specialist (the till) and reception. Not a clinician, not the accountant.
     */
    public boolean canLinkPayerPlan(UUID userId, UUID hospitalId) {
        return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE, BILLING_SPECIALIST_ROLE, RECEPTIONIST_ROLE);
    }

    public void validateRoleOrThrow(UUID userId, UUID hospitalId, String roleCode) {
        if (!hasAnyCode(userId, hospitalId, roleCode)) {
            // #1096 review: the user does not hold the role (role.notfound is a role that does not
            // exist); the role is named by its label in the reader's language, never by its code.
            throw new BusinessException("role.notHeld", roleLabel(roleCode));
        }
    }

    /**
     * The chosen assignment ITSELF holds the role (#1096 review): it is active, it is at
     * {@code hospitalId}, and its role is {@code roleCode}. Checking the assignment's user
     * instead accepted an inactive or a non-doctor assignment of a user who holds an active
     * doctor assignment elsewhere.
     */
    public void requireAssignmentHolds(UserRoleHospitalAssignment assignment, UUID hospitalId, String roleCode) {
        if (assignment == null || !Boolean.TRUE.equals(assignment.getActive())) {
            throw new FieldBusinessException("assignmentId", "role.assignment.inactive");
        }
        String held = assignment.getRole() == null || assignment.getRole().getCode() == null
            ? "" : assignment.getRole().getCode().trim().toUpperCase(Locale.ROOT);
        boolean atHospital = assignment.getHospital() != null && hospitalId != null
            && hospitalId.equals(assignment.getHospital().getId());
        if (!atHospital || !expandCodes(roleCode).contains(held)) {
            throw new FieldBusinessException("assignmentId", "role.assignment.wrongRole", roleLabel(roleCode));
        }
    }

    /**
     * "ROLE_DOCTOR" read as the role's label in the reader's language ("Médecin"); a code with
     * no label reads as itself, never as a raw bundle key (#1096 review).
     */
    private static String roleLabel(String roleCode) {
        String code = roleCode == null ? "" : roleCode.trim();
        String bare = code.toUpperCase(Locale.ROOT);
        if (bare.startsWith("ROLE_")) {
            bare = bare.substring("ROLE_".length());
        }
        String label = MessageUtil.resolveOrNull(null, "email.role.label." + bare);
        return label != null ? label : code;
    }

    public boolean isAnyActiveRole(UUID userId) { return assignmentRepository.existsByUserIdAndActiveTrue(userId); }

    /* =========================================
       Convenience
       ========================================= */
    /**
     * Prescribing is a clinical act: a hospital admin no longer passes (E9 #67,
     * D5), and midwives, who prescribe throughout the OB module, have parity
     * with nurses (E9 #69).
     *
     * <p>Physician and surgeon are named because {@code RoleExpansion}'s
     * doctor-equivalence rule — a surgeon and a physician ARE doctors — runs on
     * the authorities, so {@code hasAnyAuthority('ROLE_DOCTOR',...)} on
     * {@code POST /prescriptions} already admits them, while the checks in this
     * class match the stored ASSIGNMENT code and do not know that. Without the
     * two arms the endpoint let them in and this predicate threw them out with
     * {@code prescription.only.doctor.admin}.
     */
    public boolean canCreatePrescription(UUID userId, UUID hospitalId) {
        return isDoctor(userId, hospitalId)
            || isPhysician(userId, hospitalId)
            || isSurgeon(userId, hospitalId)
            || isNurse(userId, hospitalId)
            || isMidwife(userId, hospitalId);
    }

    /**
     * Surgeon for the same reason as above: {@code POST /lab-orders} admits
     * {@code ROLE_DOCTOR}, which a surgeon holds by expansion, and the order
     * was then refused here. Midwife is deliberately NOT added — the endpoint
     * admits it but nothing has established that a midwife orders lab tests,
     * and widening that is not this change's call.
     */
    public boolean canOrderLabTests(UUID userId, UUID hospitalId) {
        return LAB_ORDERING_ROLE_CODES.stream().anyMatch(code -> hasAnyCode(userId, hospitalId, code));
    }

    /** The role codes {@link #canOrderLabTests} admits, in the order it checks them. */
    private static final java.util.List<String> LAB_ORDERING_ROLE_CODES =
        java.util.List.of(DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, NURSE_ROLE);

    /**
     * Whether this one assignment's role is one {@link #canOrderLabTests}
     * admits - the same codes, matched the way the query matches them
     * ({@code UPPER(role.code)}, bare or {@code ROLE_}-prefixed). Says nothing
     * about whether the assignment is active or where it is.
     */
    public static boolean isLabOrderingRole(UserRoleHospitalAssignment assignment) {
        String code = assignment == null || assignment.getRole() == null ? null : assignment.getRole().getCode();
        if (code == null) {
            return false;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        String bare = upper.startsWith(ROLE_PREFIX) ? upper.substring(ROLE_PREFIX.length()) : upper;
        return LAB_ORDERING_ROLE_CODES.contains(bare);
    }

    public void requireLabScientistOrAdmin(UUID userId, UUID hospitalId) {
        if (!(isLabScientist(userId, hospitalId) || isHospitalAdmin(userId, hospitalId))) {
            throw new BusinessException("auth.lab.required");
        }
    }

    public void requireLabStaffOrAdmin(UUID userId, UUID hospitalId) {
        if (!(isLabStaff(userId, hospitalId) || isHospitalAdmin(userId, hospitalId))) {
            throw new BusinessException("auth.lab.staff.required");
        }
    }

    public boolean canLinkInsurance(UUID actorUserId, UUID hospitalId) {
        // Staff can link insurance if they have any role in the hospital
        if (isStaffOrAdminFromAuth()) {
            return assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(actorUserId, hospitalId);
        }
        // Patients can only link their own insurance
        return actorUserId != null && actorUserId.equals(getCurrentUserId());
    }
    public boolean canViewPatient(UUID actorUserId, UUID patientId) {
        // Staff can view any patient in their hospital
        if (isStaffOrAdminFromAuth()) {
            return assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(actorUserId, getCurrentHospitalId());
        }
        // Patients can only view themselves
        return actorUserId != null && actorUserId.equals(patientId);
    }

}
