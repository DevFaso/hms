package com.example.hms.service;

import com.example.hms.cdshooks.CdsCriticalBlockException;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.model.PatientAllergy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Optional;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PrescriptionServiceImpl implements PrescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(PrescriptionServiceImpl.class);

    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): the
    // i18n key for "prescription not found" appears 5x in this file.
    private static final String PRESCRIPTION_NOT_FOUND = "prescription.notfound";

    /** Matches the digest LabOrderServiceImpl already computes for lab orders. */
    private static final String SIGNATURE_ALGORITHM = "SHA-256";

    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final PrescriptionMapper prescriptionMapper;
    private final RoleValidator roleValidator;
    private final AuthService authService; // exposes UUID getCurrentUserId()
    private final UserRoleHospitalAssignmentRepository urhaRepository;
    private final CdsRuleEngine cdsRuleEngine;
    private final com.example.hms.service.pharmacy.ControlledSubstanceGuard controlledSubstanceGuard;
    private final com.example.hms.service.pharmacy.PharmacistVerificationService pharmacistVerificationService;
    private final RecordAccessPolicy recordAccessPolicy;
    /** Resolves a user id from either principal shape; see {@link #requireOwnPrescriptionWhenPatient}. */
    private final com.example.hms.controller.support.ControllerAuthUtils authUtils;
    /**
     * From config/TimeConfig, as {@code PrescriptionClarificationService}
     * takes it: the two halves of a clarification are stamped by the same
     * clock. The signing and co-signing timestamps a few methods up still
     * call {@code LocalDateTime.now()} — pre-existing, and changing them is
     * not this PR's business.
     */
    private final java.time.Clock clock;
    private final CrossHospitalReachRecorder reachRecorder;

    @Override
    @Transactional
    public PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request, Locale locale) {
        rejectClientAssertedWorkflowStatus(request);
        UUID currentUserId = authService.getCurrentUserId();
        Patient patient = resolvePatient(request, locale);

    Staff staff = resolveStaffContext(request, currentUserId);
    Encounter encounter = resolveEncounterContext(request, patient, staff);

        ensureContextConsistency(patient, staff, encounter);

        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        if (!roleValidator.canCreatePrescription(currentUserId, hospitalId)) {
            throw new BusinessException("prescription.only.doctor.admin");
        }

        UserRoleHospitalAssignment prescriberAssignment =
            resolvePrescriberAssignmentOrThrow(staff, encounter, hospitalId);

        // DECISION SUPPORT: Check allergies before prescribing
        checkAllergyConflicts(patient, hospitalId, request.getMedicationName(), request.getForceOverride());

        // CDS rule engine: drug-drug, duplicate-order, pediatric-dose
        List<CdsCard> advisories = runCdsRuleEngine(patient, hospitalId, request);

        Prescription entity = prescriptionMapper.toEntity(request, patient, staff, encounter);
        entity.setAssignment(prescriberAssignment);
        controlledSubstanceGuard.requireSafeguardsFor(entity, entity.getStatus());

        Prescription saved = prescriptionRepository.save(entity);
        PrescriptionResponseDTO response = prescriptionMapper.toResponseDTO(saved);
        response.setCdsAdvisories(advisories);
        return response;
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO getPrescriptionById(UUID id, Locale locale) {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        // Scope first, before the row is read: a caller refused for having no
        // hospital must get the same answer whether or not the id is real.
        UUID hospitalId = resolvePrescriptionReadScope();
        Prescription prescription = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        if (isReadableFromScope(prescription, hospitalId)) {
            requireOwnPrescriptionWhenPatient(prescription, auth);
        } else if (!(ReaderRolePredicates.holdsPatientRole(auth) && callerOwns(prescription, auth))) {
            // Staff who are also patients: the fallback #754 gave the
            // encounter reads. A nurse at hospital A who was a patient at
            // hospital B is a clinical reader here, so she is held to A, which
            // refused her her OWN prescription from B on an endpoint that
            // admits ROLE_PATIENT. Owning it opens it wherever she works, but
            // only with the patient grant: a staff account linked to a patient
            // row whose ROLE_PATIENT was never granted, or was revoked, must
            // not read that row across hospitals through its clinical role.
            // Ownership proven here also settles the patient-only check, so a
            // pure patient scoped elsewhere reads their own the same way.
            //
            // The super-admin exception ReaderRolePredicates.holdsPatientRole
            // documents, weighed for this caller: an expanded super-admin
            // holds ROLE_PATIENT, and gains through this only prescriptions
            // their OWN account owns at a hospital other than the one they
            // pinned; an unverified one never gets here
            // (resolvePrescriptionReadScope). resolve-clarification also reads
            // through here and is ROLE_DOCTOR-only; for it the fallback can
            // only turn a 404 on the read-back of the doctor's own
            // prescription into the record.
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }
        return prescriptionMapper.toResponseDTO(prescription);
    }

    /**
     * The hospital a by-id read is bounded by, or {@code null} for a verified
     * super-admin in global view: the rule #746 gave the encounter reads.
     *
     * <p>{@code requireActiveHospitalId()} returns {@code null} on two roads.
     * Step 1 is {@code HospitalContext.isSuperAdmin()}, which is what
     * {@code isSuperAdminFromJwtClaim()} reads. Step 4 is the safety net that
     * fires when that flag is FALSE yet the authorities collection says
     * super-admin, the road {@code RoleValidator}'s own javadoc warns an
     * impersonation context could take. {@link #findWithinHospitalScope} treats
     * any {@code null} as unscoped, so step 4 read every tenant's
     * prescriptions. Here a {@code null} is unscoped only when the verified
     * flag agrees; otherwise the caller is refused before the lookup, with the
     * answer any caller who has no hospital gets.
     *
     * <p>{@link #getPrescriptionAfterWrite} keeps {@link #findWithinHospitalScope}
     * unchanged: its contract is pinned by
     * {@code PrescriptionAfterWriteCallerGuardTest}.
     */
    private UUID resolvePrescriptionReadScope() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null && !roleValidator.isSuperAdminFromJwtClaim()) {
            throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }
        return hospitalId;
    }

    /**
     * Inside the boundary {@link #resolvePrescriptionReadScope} worked out: any
     * prescription for a verified super-admin in global view, otherwise one at
     * the caller's hospital. A prescription with no hospital is outside: the
     * one we cannot place is exactly the one not to hand out.
     * {@code Prescription.hospital} is {@code nullable = false}, so no stored
     * row reaches that branch.
     */
    private static boolean isReadableFromScope(Prescription prescription, UUID hospitalId) {
        if (hospitalId == null) {
            return true;
        }
        return prescription.getHospital() != null
            && hospitalId.equals(prescription.getHospital().getId());
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO getPrescriptionAfterWrite(UUID id, Locale locale) {
        // No ownership guard: the caller has just been authorised for, and has
        // committed, a write on this prescription. See the interface javadoc.
        return prescriptionMapper.toResponseDTO(findWithinHospitalScope(id));
    }

    private Prescription findWithinHospitalScope(UUID id) {
        Prescription prescription = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));

        // ── Hospital scope enforcement ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && prescription.getHospital() != null
                && !prescription.getHospital().getId().equals(hospitalId)) {
            // Return 404 (not 403) to avoid info leakage
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }
        return prescription;
    }

    /**
     * A patient may read their own prescription and no one else's.
     *
     * <p>The hospital scope above is not this check. It bounded the leak — a
     * patient could only reach prescriptions at the hospital their own
     * assignment resolves to — but within that hospital any prescription id
     * returned somebody else's medication, dose, frequency, duration and
     * instructions. Stripping the clarification exchange in the controller is
     * about the pharmacist's notes, not about whose prescription it is.
     *
     * <p>404, not 403, and the same message as a prescription that does not
     * exist: the answer must not tell a patient that an id is real — including
     * when the principal itself cannot be resolved to a patient row, and
     * without a data defect turning the refusal into a stack trace. The
     * subject comes from the authenticated principal
     * ({@code user id → Patient}), never from anything in the request — the
     * rule {@code PatientPortalServiceImpl.resolvePatientId} already follows
     * for every {@code /me/patient/*} read, through the same resolver.
     *
     * <p>A no-op for every role this read admits, including a clinician who is
     * also a patient at the hospital — see {@link PrescriptionReaderRoles},
     * which mirrors the endpoint’s own annotation and records what that costs
     * on the OIDC path. The write endpoints that admit roles this read does not
     * go through {@link #getPrescriptionAfterWrite} instead.
     */
    private void requireOwnPrescriptionWhenPatient(Prescription prescription,
                                                   org.springframework.security.core.Authentication auth) {
        if (PrescriptionReaderRoles.isPatientOnly(auth) && !callerOwns(prescription, auth)) {
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }
    }

    /**
     * Is this prescription's patient row linked to the caller's user account?
     * Shared by the patient-only check and the staff-owner fallback so the two
     * cannot disagree about who owns what.
     */
    private boolean callerOwns(Prescription prescription,
                               org.springframework.security.core.Authentication auth) {
        // authUtils, not authService.getCurrentUserId(): the latter resolves
        // only a CustomUserDetails principal and throws 401 on a
        // JwtAuthenticationToken, so on the OIDC path it would refuse the
        // owner their own prescription. ControllerAuthUtils.resolveUserId
        // reads the appUserId claim too, and is what
        // PatientPortalServiceImpl.resolvePatientId already uses.
        UUID subjectPatientId = prescription.getPatient() != null
            ? prescription.getPatient().getId()
            : null;
        // existsByIdAndUserId, not findByUserId: the single-result finder throws
        // IncorrectResultSizeDataAccessException on a tenant that still carries
        // duplicate clinical.patients.user_id rows (V113 falls back to a plain
        // index rather than failing the deploy), which would answer a 500 where
        // this method promises a 404 or the record. The membership form cannot,
        // it stays right however many rows the account owns, and it decides the
        // question without materialising a Patient and decrypting its PHI.
        return subjectPatientId != null
            && authUtils.resolveUserId(auth)
                .map(userId -> patientRepository.existsByIdAndUserId(subjectPatientId, userId))
                .orElse(false);
    }

    /**
     * The signing ceremony (P2 #16).
     *
     * <p>Before this, {@code SIGNED} was a string a client could put in a
     * request body: {@link PrescriptionMapper} wrote {@code dto.getStatus()}
     * straight onto the entity, so "signed" meant nothing more than "somebody
     * sent the word SIGNED". This is now the only path that reaches that status,
     * and it leaves evidence behind — signer, instant, and a SHA-256 digest of
     * what was signed.
     *
     * <p>The digest is tamper-evidence, not a PKI credential. Editing a signed
     * prescription afterwards leaves the stored digest disagreeing with the row,
     * so recomputing {@link #canonicalSignaturePayload} over the current values
     * reveals the edit. Note what that does and does not give you: nothing in
     * this codebase recomputes it yet, so the digest is evidence available to an
     * audit, not an active guard. {@code updatePrescription} still permits edits
     * to a signed prescription — it is the disagreement that makes them
     * visible, not a refusal.
     */
    @Override
    @Transactional
    public PrescriptionResponseDTO signPrescription(UUID id, Locale locale) {
        Prescription prescription = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));

        // Same 404-not-403 idiom as getPrescriptionById: a prescription at
        // another hospital must not be distinguishable from one that does not
        // exist.
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && prescription.getHospital() != null
                && !prescription.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }

        // Re-signing is refused rather than made idempotent. A second signature
        // over altered content would silently replace the first, destroying the
        // only record of what was originally authorised. Checked before the
        // identity test so that "this is already signed" is the answer even when
        // the caller is not the prescriber.
        if (prescription.getSignatureValue() != null) {
            throw new BusinessException(
                "This prescription has already been signed. A signature cannot be reissued; "
                    + "cancel it and write a new prescription instead.");
        }

        PrescriptionStatus status = prescription.getStatus();
        if (status != null && status != PrescriptionStatus.DRAFT
                && status != PrescriptionStatus.PENDING_SIGNATURE) {
            throw new BusinessException(
                "Only a prescription in DRAFT or PENDING_SIGNATURE can be signed; this one is "
                    + status + ".");
        }

        Staff signer = resolveSigningPrescriber(prescription);

        // Checked against the status we are about to move to, not the one the
        // row still holds — a controlled substance must not become signable
        // just because it is currently a draft.
        controlledSubstanceGuard.requireSafeguardsFor(prescription, PrescriptionStatus.SIGNED);

        LocalDateTime signedAt = LocalDateTime.now();
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setSignedBy(signer);
        prescription.setSignedAt(signedAt);
        prescription.setSignatureAlgorithm(SIGNATURE_ALGORITHM);
        prescription.setSignatureValue(
            computeSignatureDigest(canonicalSignaturePayload(prescription, signer, signedAt)));

        logger.info("Prescription {} signed by staff {}", prescription.getId(), signer.getId());
        return prescriptionMapper.toResponseDTO(prescriptionRepository.save(prescription));
    }

    /**
     * The co-sign ceremony (P2 #15).
     *
     * <p>First writer {@code cosignedBy}/{@code cosignedAt} have ever had. The
     * co-signer must hold a prescribing role at the prescription's hospital and
     * must NOT be the prescription's own prescriber — the requirement exists to
     * put a second clinician's judgment on record, and letting the prescriber
     * co-sign their own order would satisfy the letter of the gate while
     * deleting its point.
     */
    @Override
    @Transactional
    public PrescriptionResponseDTO cosignPrescription(UUID id, Locale locale) {
        Prescription prescription = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));

        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && prescription.getHospital() != null
                && !prescription.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }

        // Resolved BEFORE the workflow-state checks: their messages tell the
        // caller whether an order requires a co-signature, whether it already
        // has one and what status it is in, and someone with no prescribing
        // assignment here has no business reading that back.
        Staff cosigner = resolveCosignerAtHospital(prescription);

        if (!prescription.isRequiresCosign()) {
            throw new BusinessException(
                "This prescription does not declare a co-signature requirement.");
        }
        if (prescription.getCosignedAt() != null || prescription.getCosignedBy() != null) {
            throw new BusinessException("This prescription has already been co-signed.");
        }

        PrescriptionStatus status = prescription.getStatus();
        if (status != null && status != PrescriptionStatus.DRAFT
                && status != PrescriptionStatus.PENDING_SIGNATURE) {
            throw new BusinessException(
                "Only a prescription in DRAFT or PENDING_SIGNATURE can be co-signed; this one is "
                    + status + ".");
        }

        Staff prescriber = prescription.getStaff();
        if (prescriber != null && prescriber.getId() != null
                && prescriber.getId().equals(cosigner.getId())) {
            throw new BusinessException(
                "A prescription cannot be co-signed by its own prescriber; the co-signature "
                    + "exists to put a second clinician's judgment on record.");
        }

        prescription.setCosignedBy(cosigner);
        prescription.setCosignedAt(LocalDateTime.now());

        logger.info("Prescription {} co-signed by staff {}", prescription.getId(), cosigner.getId());
        return prescriptionMapper.toResponseDTO(prescriptionRepository.save(prescription));
    }

    /**
     * The co-signer, credentialed at the PRESCRIPTION's hospital.
     *
     * <p>The method's own contract says the co-signer "must hold a prescribing
     * role at the prescription's hospital", but nothing checked it: the lookup
     * was {@code findFirstByUserIdOrderByCreatedAtAsc}, "any staff profile this
     * user has, anywhere". Holding {@code ROLE_DOCTOR} somewhere plus a staff
     * row somewhere was enough to put a co-signature on an order at a hospital
     * the caller has no active assignment at.
     *
     * <p>Where the credential lives matters here. {@code staff.user_id} is
     * UNIQUE (entity {@code uq_staff_user}, and V8 de-duplicated and indexed
     * it), so a clinician has exactly ONE staff row no matter how many
     * hospitals they work at; multi-hospital membership is modelled by
     * {@link UserRoleHospitalAssignment}. Anchoring the CREDENTIAL check on a
     * staff row at the prescription's hospital would therefore refuse the
     * legitimate cross-hospital co-signer — one staff row filed at hospital A,
     * an active doctor assignment at hospital B — which is exactly the person
     * this is meant to admit. So: the assignment answers "are you a prescriber
     * here", the staff row is only the FK {@code cosignedBy} records.
     *
     * <p>The anchor is the prescription's own hospital, not the acting scope.
     * The co-signature attests to an order that belongs to that hospital and
     * will be filled there. With a scope active the check at the top of
     * {@code cosignPrescription} has already proved the two are the same
     * hospital; in the unscoped (global) view the prescription's hospital is
     * the only defined anchor, so reading the scope would leave nothing to
     * check.
     *
     * <p>Doctor/physician/surgeon, because {@code RoleExpansion} makes a
     * physician and a surgeon a doctor before the controller's
     * {@code hasAuthority('ROLE_DOCTOR')} runs, while the per-hospital
     * {@code RoleValidator} checks match the stored assignment code and do not
     * know that. Naming only DOCTOR here would refuse people the annotation
     * admits.
     *
     * <p>AccessDenied rather than BusinessException: this is an authorization
     * failure, so 403 rather than 400 — the clarification path's stance.
     */
    private Staff resolveCosignerAtHospital(Prescription prescription) {
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("Unable to determine the co-signing clinician.");
        }
        // Prescription.hospital is optional=false on a NOT NULL column, so this
        // is never null in practice; a null would make every check below false
        // and refuse, which is the safe direction anyway.
        UUID rxHospitalId = prescription.getHospital() != null
            ? prescription.getHospital().getId()
            : null;
        boolean prescriberHere = roleValidator.isDoctor(currentUserId, rxHospitalId)
            || roleValidator.isPhysician(currentUserId, rxHospitalId)
            || roleValidator.isSurgeon(currentUserId, rxHospitalId);
        if (!prescriberHere) {
            throw new AccessDeniedException(
                "Only a clinician with an active prescribing assignment at the prescribing "
                    + "hospital can co-sign a prescription.");
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId)
            .orElseThrow(() -> new AccessDeniedException(
                "Only a clinician with a staff profile can co-sign a prescription."));
    }

    /**
     * Signing is the prescriber's own act.
     *
     * <p>The controller's {@code @PreAuthorize} only establishes that the caller
     * holds a prescribing role somewhere; it cannot express "and this is your
     * prescription". Without this check any doctor in the hospital could sign a
     * colleague's prescription, which is the one thing a signature is supposed
     * to rule out. Co-signature is a separate act with its own columns and must
     * not borrow this path.
     *
     * <p>AccessDeniedException rather than BusinessException: this is an
     * authorization failure, not a workflow one, and the caller should get 403
     * rather than 400.
     */
    private Staff resolveSigningPrescriber(Prescription prescription) {
        Staff prescriber = prescription.getStaff();
        UUID prescriberUserId = prescriber != null && prescriber.getUser() != null
            ? prescriber.getUser().getId()
            : null;

        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null || prescriberUserId == null
                || !prescriberUserId.equals(currentUserId)) {
            throw new AccessDeniedException(
                "Only the prescribing clinician can sign this prescription.");
        }
        return prescriber;
    }

    /**
     * The content the signature attests to.
     *
     * <p>Field order and the separator are part of the contract: a digest is
     * only comparable against one recomputed the same way. Nulls are rendered
     * explicitly so that a missing dose and an empty dose cannot collide into
     * the same digest.
     */
    private String canonicalSignaturePayload(Prescription prescription, Staff signer, LocalDateTime signedAt) {
        return String.join("|",
            String.valueOf(prescription.getId()),
            String.valueOf(prescription.getPatient() != null ? prescription.getPatient().getId() : null),
            String.valueOf(prescription.getMedicationName()),
            String.valueOf(prescription.getMedicationCode()),
            String.valueOf(prescription.getDosage()),
            String.valueOf(prescription.getFrequency()),
            String.valueOf(prescription.getDuration()),
            String.valueOf(signer.getId()),
            String.valueOf(signedAt));
    }

    private String computeSignatureDigest(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SIGNATURE_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(SIGNATURE_ALGORITHM + " algorithm not available", e);
        }
    }

    /**
     * Statuses a request body may assert. Everything else belongs to a server
     * workflow — SIGNED to the signing ceremony, TRANSMITTED/DISPENSED and the
     * partner states to dispatch and pharmacy, PENDING_CLARIFICATION to the
     * pharmacist's clarification ceremony (gap G5, which records the reason
     * and notifies the prescriber; asserting the word would skip both) — and
     * being able to assert one in a create/update body is a bypass of
     * whichever ceremony owns it.
     */
    private static final java.util.Set<PrescriptionStatus> CLIENT_ASSERTABLE_STATUSES =
        java.util.EnumSet.of(
            PrescriptionStatus.DRAFT,
            PrescriptionStatus.PENDING_SIGNATURE,
            PrescriptionStatus.CANCELLED,
            PrescriptionStatus.DISCONTINUED);

    /**
     * A client cannot assert a workflow status by sending its name.
     *
     * <p>{@code PrescriptionMapper} copies {@code dto.getStatus()} onto the
     * entity, which is how SIGNED came to mean nothing. The 2026-08-21
     * reassessment showed blocking only SIGNED left the ceremony bypassable for
     * its actual purpose: a client-asserted TRANSMITTED still landed on the
     * entity, and TRANSMITTED is dispensable — the guard protected the word,
     * not the capability. Nothing in the backend ever writes TRANSMITTED, so
     * the only writer it ever had was this hole.
     *
     * <p>It refuses rather than silently downgrading: a caller that believed it
     * was signing (or transmitting) must not be told it succeeded.
     */
    private void rejectClientAssertedWorkflowStatus(PrescriptionRequestDTO request) {
        PrescriptionStatus status = request.getStatus();
        if (status != null && !CLIENT_ASSERTABLE_STATUSES.contains(status)) {
            throw new BusinessException(
                "The status " + status + " cannot be set directly. Signing has its own endpoint "
                    + "(POST /prescriptions/{id}/sign); transmission and dispensing are recorded by "
                    + "their own workflows.");
        }
    }

    /**
     * The status rule on an edit, which applies only while the order is
     * awaiting a pharmacist's clarification. Everything else keeps the
     * client-assertable rule exactly as it was.
     *
     * <p>The portal echoes the current status into every PUT, so a PUT that
     * echoes PENDING_CLARIFICATION is a no-op and is accepted — otherwise the
     * "doctor edits first" path of gap G5 would be refused for saying what
     * the row already says. The exemption is deliberately narrow: an echoed
     * DISPENSED or PARTNER_ACCEPTED must still be refused, because
     * {@code updatePrescription} rewrites the drug and the dose and those
     * statuses mean the medication has already left the counter.
     *
     * <p>Withdrawal is the one status change permitted from
     * PENDING_CLARIFICATION: a contraindicated order must be cancellable on
     * the spot, not first resolved back into the pharmacy queue. The open
     * clarification is closed as it goes, so nothing reads it as a question
     * still waiting for an answer. Any other status is refused with 409 —
     * resolve-clarification is the only way back into the queue.
     */
    private void rejectStatusChangeOnUpdate(Prescription existing, PrescriptionRequestDTO request) {
        PrescriptionStatus requested = request.getStatus();
        if (requested == null) {
            return;
        }
        if (existing.getStatus() != PrescriptionStatus.PENDING_CLARIFICATION) {
            rejectClientAssertedWorkflowStatus(request);
            return;
        }
        if (requested == PrescriptionStatus.PENDING_CLARIFICATION) {
            return;
        }
        if (requested == PrescriptionStatus.CANCELLED || requested == PrescriptionStatus.DISCONTINUED) {
            closeClarificationOnWithdrawal(existing);
            return;
        }
        throw new ConflictException(
            "This prescription is awaiting the prescriber's clarification; edit it and answer with "
                + "POST /prescriptions/{id}/resolve-clarification, or cancel it, rather than changing "
                + "its status.");
    }

    /**
     * Withdrawing an order answers the pharmacist's question in the only way
     * that matters, so the clarification is stamped resolved by whoever
     * withdrew it. The reason and the previous status stay on the row as the
     * record of what was asked and from where. The PUT itself is audited by
     * convention (WriteAuditInterceptor DATA_UPDATE).
     */
    private void closeClarificationOnWithdrawal(Prescription existing) {
        if (existing.getClarificationResolvedAt() != null) {
            return;
        }
        existing.setClarificationResolvedAt(LocalDateTime.now(clock));
        existing.setClarificationResolvedByUserId(roleValidator.getCurrentUserId());
        logger.info("Prescription {} withdrawn while awaiting clarification; the question is closed",
            existing.getId());
    }

    /**
     * A declared safeguard cannot be quietly un-declared.
     *
     * <p>Making the controlled-substance flags writable (P2 #15's actual gap —
     * the gates shipped with no way to set the flag) also creates the reverse
     * edit: clearing {@code controlledSubstance} on an update would drop the
     * two-factor gate an earlier revision of the same prescription declared it
     * needed. Setting a flag is a clinical judgment; unsetting one is refused —
     * cancel and re-prescribe instead, which leaves a record.
     */
    private void rejectSafeguardWithdrawal(Prescription existing, PrescriptionRequestDTO request) {
        if (existing.isControlledSubstance() && Boolean.FALSE.equals(request.getControlledSubstance())) {
            throw new BusinessException(
                "This prescription is flagged as a controlled substance; the flag cannot be "
                    + "removed by editing. Cancel it and write a new prescription instead.");
        }
        if (existing.isRequiresCosign() && Boolean.FALSE.equals(request.getRequiresCosign())) {
            throw new BusinessException(
                "This prescription declares a co-signature requirement; the requirement cannot be "
                    + "removed by editing. Cancel it and write a new prescription instead.");
        }
    }

    @Override
    @Transactional
    public Page<PrescriptionResponseDTO> list(UUID patientId, UUID staffId, UUID encounterId,
                                              List<PrescriptionStatus> statuses,
                                              Pageable pageable, Locale locale) {
        // Hospital scope enforcement: mandatory for non-superadmin.
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        List<PrescriptionStatus> filter = normaliseStatusFilter(statuses);

        Page<Prescription> rows;
        if (patientId != null) {
            rows = patientRows(patientId, hospitalId, filter, pageable);
        } else if (staffId != null) {
            rows = staffRows(staffId, hospitalId, filter, pageable);
        } else if (encounterId != null) {
            rows = encounterRows(encounterId, hospitalId, filter, pageable);
        } else {
            rows = tenantRows(hospitalId, filter, pageable);
        }
        return rows.map(prescriptionMapper::toResponseDTO);
    }

    /**
     * Gap G12 - the {@code status} query parameter as the repository wants it.
     *
     * <p>EMPTY means "every status", not "no status can match":
     * {@code ?status=} on a URL arrives here as a single-element list holding
     * null, and silently returning an empty page for it would be the same class
     * of lie the filter exists to remove. Duplicates collapse; order is
     * irrelevant to an IN clause.
     */
    private List<PrescriptionStatus> normaliseStatusFilter(List<PrescriptionStatus> statuses) {
        if (statuses == null) {
            return List.of();
        }
        return statuses.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * E9 #59c - the prescription list follows the patient across the readable
     * hospitals, and every foreign row on the page is accounted for.
     * Prescriptions carry no sensitivity tag (V158).
     */
    private Page<Prescription> patientRows(UUID patientId, UUID hospitalId,
                                           List<PrescriptionStatus> filter, Pageable pageable) {
        if (hospitalId == null) {
            return filter.isEmpty()
                ? prescriptionRepository.findByPatient_Id(patientId, pageable)
                : prescriptionRepository.findByPatient_IdAndStatusIn(patientId, filter, pageable);
        }
        UUID requesterUserId = authService.getCurrentUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
        Page<Prescription> rows = filter.isEmpty()
            ? prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, readable, pageable)
            : prescriptionRepository.findByPatient_IdAndHospital_IdInAndStatusIn(patientId, readable, filter, pageable);
        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(rows.getContent().stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList(), hospitalId),
            "Cross-hospital prescription read on the treatment relationship");
        return rows;
    }

    private Page<Prescription> staffRows(UUID staffId, UUID hospitalId,
                                         List<PrescriptionStatus> filter, Pageable pageable) {
        if (hospitalId == null) {
            return filter.isEmpty()
                ? prescriptionRepository.findByStaff_Id(staffId, pageable)
                : prescriptionRepository.findByStaff_IdAndStatusIn(staffId, filter, pageable);
        }
        return filter.isEmpty()
            ? prescriptionRepository.findByStaff_IdAndHospital_Id(staffId, hospitalId, pageable)
            : prescriptionRepository.findByStaff_IdAndHospital_IdAndStatusIn(staffId, hospitalId, filter, pageable);
    }

    private Page<Prescription> encounterRows(UUID encounterId, UUID hospitalId,
                                             List<PrescriptionStatus> filter, Pageable pageable) {
        if (hospitalId == null) {
            return filter.isEmpty()
                ? prescriptionRepository.findByEncounter_Id(encounterId, pageable)
                : prescriptionRepository.findByEncounter_IdAndStatusIn(encounterId, filter, pageable);
        }
        return filter.isEmpty()
            ? prescriptionRepository.findByEncounter_IdAndHospital_Id(encounterId, hospitalId, pageable)
            : prescriptionRepository.findByEncounter_IdAndHospital_IdAndStatusIn(encounterId, hospitalId, filter, pageable);
    }

    /** No id filter: the whole tenant, or the whole platform for a super-admin in global view. */
    private Page<Prescription> tenantRows(UUID hospitalId, List<PrescriptionStatus> filter, Pageable pageable) {
        if (hospitalId == null) {
            return filter.isEmpty()
                ? prescriptionRepository.findAll(pageable)
                : prescriptionRepository.findByStatusIn(filter, pageable);
        }
        return filter.isEmpty()
            ? prescriptionRepository.findByHospital_Id(hospitalId, pageable)
            : prescriptionRepository.findByHospital_IdAndStatusIn(hospitalId, filter, pageable);
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO updatePrescription(UUID id, PrescriptionRequestDTO request, Locale locale) {
        Prescription existing = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        rejectStatusChangeOnUpdate(existing, request);
        rejectSafeguardWithdrawal(existing, request);

        UUID currentUserId = authService.getCurrentUserId();

        Patient patient = resolvePatient(request, locale);

    Staff staff = resolveStaffContext(request, currentUserId);
    Encounter encounter = resolveEncounterContext(request, patient, staff);

        ensureContextConsistency(patient, staff, encounter);

        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        if (!roleValidator.canCreatePrescription(currentUserId, hospitalId)) {
            throw new BusinessException("prescription.only.doctor.admin");
        }

        UserRoleHospitalAssignment prescriberAssignment =
            resolvePrescriberAssignmentOrThrow(staff, encounter, hospitalId);

        // DECISION SUPPORT: Check allergies before updating prescription
        checkAllergyConflicts(patient, hospitalId, request.getMedicationName(), request.getForceOverride());

        // CDS rule engine: drug-drug, duplicate-order, pediatric-dose
        List<CdsCard> advisories = runCdsRuleEngine(patient, hospitalId, request);

        prescriptionMapper.updateEntity(existing, request, patient, staff, encounter);
        existing.setAssignment(prescriberAssignment);
        // Tier 2 item 33: an edit invalidates any pharmacist verification.
        // updateEntity rewrites medicationName, dosage and frequency, and
        // there is no status guard above — so a SIGNED prescription's drug
        // and dose can change here. A verification that survived that would
        // assert a check of a drug the pharmacist never saw.
        pharmacistVerificationService.invalidateOnChange(existing);
        controlledSubstanceGuard.requireSafeguardsFor(existing, existing.getStatus());

        Prescription saved = prescriptionRepository.save(existing);
        PrescriptionResponseDTO response = prescriptionMapper.toResponseDTO(saved);
        response.setCdsAdvisories(advisories);
        return response;
    }

    @Override
    @Transactional
    public void deletePrescription(UUID id, Locale locale) {
        Prescription prescription = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));

        // ── Hospital scope enforcement ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null
                && prescription.getHospital() != null
                && !prescription.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }

        prescriptionRepository.deleteById(id);
    }

    @Override
    @Transactional
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsByPatientId(UUID patientId, Locale locale) {
        // ── Hospital scope enforcement ──
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null) {
            // E9 #59c — see list(): the same widening, unpaged.
            UUID requesterUserId = authService.getCurrentUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
            List<Prescription> rows = prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, readable);
            reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(rows.stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList(), hospitalId),
                "Cross-hospital prescription read on the treatment relationship");
            return rows.stream()
                .map(prescriptionMapper::toResponseDTO)
                .toList();
        }
        return prescriptionRepository.findByPatient_Id(patientId, Pageable.unpaged()).stream()
            .map(prescriptionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsByStaffId(UUID staffId, Locale locale) {
        return prescriptionRepository.findByStaff_Id(staffId, Pageable.unpaged())
            .getContent().stream()
            .map(prescriptionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsByEncounterId(UUID encounterId, Locale locale) {
        return prescriptionRepository.findByEncounter_Id(encounterId, Pageable.unpaged())
            .getContent().stream()
            .map(prescriptionMapper::toResponseDTO)
            .toList();
    }

    /* ============================
       Helpers
       ============================ */

    @SuppressWarnings("java:S1172") // locale reserved for i18n error messages
    private Patient resolvePatient(PrescriptionRequestDTO request, Locale locale) {
        // Use findByIdUnscoped: multi-hospital patients have Patient.hospitalId
        // set to their first hospital, so the tenant-scoped findById misses them
        // when accessed from a different hospital. Security is enforced via
        // ensureContextConsistency (encounter ↔ patient ↔ staff ↔ hospital).
        if (request.getPatientId() != null) {
            return patientRepository.findByIdUnscoped(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
        }
        if (StringUtils.hasText(request.getPatientIdentifier())) {
            String identifier = request.getPatientIdentifier().trim();
            Optional<Patient> viaUsernameOrEmail = patientRepository.findByUsernameOrEmail(identifier);
            if (viaUsernameOrEmail.isPresent()) {
                return viaUsernameOrEmail.get();
            }

            List<Patient> byMrn = patientRepository.findByMrn(identifier);
            if (!byMrn.isEmpty()) {
                return byMrn.get(0);
            }

            try {
                UUID parsed = UUID.fromString(identifier);
                return patientRepository.findByIdUnscoped(parsed)
                    .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
            } catch (IllegalArgumentException ignore) {
                // not a UUID, fall through
            }

            throw new ResourceNotFoundException("patient.notfound");
        }
        throw new BusinessException("prescription.patient.required");
    }

    private void ensureContextConsistency(Patient patient, Staff staff, Encounter encounter) {
        if (encounter.getHospital() == null) {
            throw new BusinessException("prescription.hospital.link.required");
        }
        if (encounter.getPatient() == null || !encounter.getPatient().getId().equals(patient.getId())) {
            throw new BusinessException("prescription.encounter.patient.mismatch");
        }
        if (encounter.getStaff() == null || !encounter.getStaff().getId().equals(staff.getId())) {
            throw new BusinessException("prescription.encounter.staff.mismatch");
        }
        if (staff.getHospital() == null || !staff.getHospital().getId().equals(encounter.getHospital().getId())) {
            throw new BusinessException("prescription.encounter.staff.hospital.mismatch");
        }
    }

    /**
     * Determine the prescriber's assignment to satisfy NOT NULL FK:
     * 1) encounter.assignment (best, immutable snapshot)
     * 2) staff.assignment / URHA lookup for doctor role within the hospital scope
     */
    private UserRoleHospitalAssignment resolvePrescriberAssignmentOrThrow(Staff staff, Encounter encounter, UUID hospitalId) {
        if (encounter != null && encounter.getAssignment() != null) {
            return encounter.getAssignment();
        }
        return resolveAssignmentForStaff(staff, hospitalId);
    }

    private Staff resolveStaffContext(PrescriptionRequestDTO request, UUID currentUserId) {
        if (request.getStaffId() != null) {
            return staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));
        }
        if (currentUserId == null) {
            throw new BusinessException("prescription.staff.context.missing");
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId)
            .orElseThrow(() -> new BusinessException("prescription.staff.context.missing"));
    }

    private Encounter resolveEncounterContext(PrescriptionRequestDTO request,
                                              Patient patient,
                                              Staff staff) {
        if (request.getEncounterId() != null) {
            return encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound"));
        }

        UUID hospitalId = determineHospitalId(staff, patient);
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        Optional<Encounter> existing = encounterRepository
            .findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
                patient.getId(), staff.getId(), hospitalId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserRoleHospitalAssignment assignment = resolveAssignmentForStaff(staff, hospitalId);
        return createEncounterSnapshot(patient, staff, assignment);
    }

    private UUID determineHospitalId(Staff staff, Patient patient) {
        if (staff != null && staff.getHospital() != null) {
            return staff.getHospital().getId();
        }
        if (patient != null && patient.getHospitalId() != null) {
            return patient.getHospitalId();
        }
        return roleValidator.getCurrentHospitalId();
    }

    private Encounter createEncounterSnapshot(Patient patient,
                                              Staff staff,
                                              UserRoleHospitalAssignment assignment) {
        if (staff.getHospital() == null) {
            throw new BusinessException("prescription.staff.hospital.missing");
        }
        Encounter encounter = Encounter.builder()
            .patient(patient)
            .staff(staff)
            .hospital(staff.getHospital())
            .assignment(assignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now())
            .status(EncounterStatus.IN_PROGRESS)
            .notes("Auto-generated for prescription entry")
            .build();
        encounter.setCode(generateEncounterCode());
        return encounterRepository.save(encounter);
    }

    private String generateEncounterCode() {
        String date = LocalDate.now().toString().replace("-", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "ENC-" + date + "-" + suffix;
    }

    private UserRoleHospitalAssignment resolveAssignmentForStaff(Staff staff, UUID hospitalId) {
        if (staff == null) {
            throw new BusinessException("prescription.staff.notfound");
        }
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        UserRoleHospitalAssignment fromStaff = staff.getAssignment();
        if (fromStaff != null && fromStaff.getHospital() != null
            && hospitalId.equals(fromStaff.getHospital().getId())) {
            return fromStaff;
        }

        UUID staffUserId = staff.getUser() != null ? staff.getUser().getId() : null;
        if (staffUserId == null) {
            throw new BusinessException("prescription.assignment.missing.staff.user");
        }

        Optional<UserRoleHospitalAssignment> viaDoctor =
            urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(staffUserId, hospitalId, "DOCTOR");
        if (viaDoctor.isEmpty()) {
            viaDoctor = urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(staffUserId, hospitalId, "ROLE_DOCTOR");
        }

        return viaDoctor.orElseThrow(() -> new BusinessException("prescription.assignment.missing"));
    }

    /**
     * Check patient allergies against the medication being prescribed.
     * Logs warnings and throws BusinessException if severe allergy found without override.
     */
    private void checkAllergyConflicts(Patient patient, UUID hospitalId, String medicationName, Boolean forceOverride) {
        if (medicationName == null || medicationName.isBlank()) {
            return; // Cannot check without medication name
        }

        List<PatientAllergy> allergies = patientAllergyRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospitalId);
        String medLower = medicationName.toLowerCase();
        
        for (PatientAllergy allergy : allergies) {
            if (allergy.isActive() && matchesAllergen(medLower, allergy.getAllergenDisplay())) {
                handleAllergyConflict(patient, allergy, medicationName, forceOverride);
            }
        }
    }

    private boolean matchesAllergen(String medLower, String allergenDisplay) {
        if (allergenDisplay == null) {
            return false;
        }
        String allergenLower = allergenDisplay.toLowerCase();
        return allergenLower.contains(medLower) || medLower.contains(allergenLower);
    }

    private void handleAllergyConflict(Patient patient, PatientAllergy allergy, String medicationName, Boolean forceOverride) {
        String severityStr = allergy.getSeverity() != null ? allergy.getSeverity().name() : "UNKNOWN";
        String reaction = allergy.getReaction() != null ? allergy.getReaction() : "unspecified reaction";

        logger.warn("ALLERGY ALERT: Patient {} has documented allergy to '{}' (severity: {}, reaction: {}). " +
                   "Attempted to prescribe '{}'. Override: {}",
                   patient.getId(), sanitizeLog(allergy.getAllergenDisplay()), severityStr,
                   sanitizeLog(reaction), sanitizeLog(medicationName), forceOverride);
        
        if (isSevereAllergy(allergy.getSeverity()) && !Boolean.TRUE.equals(forceOverride)) {
            throw new BusinessException(
                String.format("ALLERGY CONFLICT: Patient has a documented %s allergy to '%s' with reaction: %s. " +
                            "Cannot prescribe '%s' without explicit override. Review patient allergies before proceeding.",
                            severityStr, allergy.getAllergenDisplay(), reaction, medicationName)
            );
        }
        
        logger.info("Prescription proceeding with documented {} allergy to '{}'", severityStr, allergy.getAllergenDisplay());
    }

    private boolean isSevereAllergy(com.example.hms.enums.AllergySeverity severity) {
        if (severity == null) {
            return false;
        }
        return severity == com.example.hms.enums.AllergySeverity.SEVERE 
            || severity == com.example.hms.enums.AllergySeverity.LIFE_THREATENING;
    }

    /** Strip newline/carriage-return characters to prevent log-injection attacks (S5145). */
    private static String sanitizeLog(String value) {
        if (value == null) return null;
        return value.replaceAll("[\r\n\t]", "_");
    }

    /**
     * Runs the CDS rule engine for the proposed prescription, blocks on
     * any critical finding unless {@code forceOverride=true}, and
     * returns the full advisory list (caller attaches it to the
     * response DTO). Mirrors the existing severe-allergy gate so the
     * override surface stays uniform; on block, throws
     * {@link CdsCriticalBlockException} so the API caller receives a
     * structured payload instead of having to parse the message text.
     */
    private List<CdsCard> runCdsRuleEngine(Patient patient, UUID hospitalId,
                                           PrescriptionRequestDTO request) {
        List<CdsCard> advisories = cdsRuleEngine.evaluateProposedPrescription(
            patient, hospitalId,
            request.getMedicationName(),
            request.getMedicationCode(),
            request.getDosage());
        if (advisories.isEmpty() || Boolean.TRUE.equals(request.getForceOverride())) {
            return advisories;
        }
        for (CdsCard card : advisories) {
            if (card.indicator() == CdsCard.Indicator.CRITICAL) {
                if (logger.isWarnEnabled()) {
                    logger.warn("CDS critical advisory blocked prescription: {}",
                        sanitizeLog(card.summary()));
                }
                throw new CdsCriticalBlockException(
                    "CDS ALERT: " + card.summary()
                        + " — set forceOverride after clinical review.",
                    advisories);
            }
        }
        return advisories;
    }

}
