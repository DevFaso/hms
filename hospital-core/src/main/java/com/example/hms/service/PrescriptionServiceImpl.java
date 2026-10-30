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
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
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
import com.example.hms.service.recordaccess.NeverSentRule;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PrescriptionServiceImpl implements PrescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(PrescriptionServiceImpl.class);

    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): the
    // i18n key for "prescription not found" appears 5x in this file.
    private static final String PRESCRIPTION_NOT_FOUND = "prescription.notfound";
    private static final String CROSS_HOSPITAL_READ = "Cross-hospital prescription read on the treatment relationship";
    private static final String NOT_ON_STAY_CARE_TEAM =
        "The prescriber does not work at the hospital of this patient's stay.";


    /** Matches the digest LabOrderServiceImpl already computes for lab orders. */
    private static final String SIGNATURE_ALGORITHM = "SHA-256";

    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
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
    /** Who is a patient on the read, and whether the prescription is theirs; see {@link #readableAsItsPatient}. */
    private final PatientSubjectReadGuard subjectReadGuard;
    /**
     * From config/TimeConfig, as {@code PrescriptionClarificationService}
     * takes it: the two halves of a clarification are stamped by the same
     * clock. The signing and co-signing timestamps a few methods up still
     * call {@code LocalDateTime.now()} — pre-existing, and changing them is
     * not this PR's business.
     */
    private final java.time.Clock clock;
    private final CrossHospitalReachRecorder reachRecorder;
    private final com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler withdrawnOrders;
    /** G15: withdrawal and edit void an open preparation (rule 8). */
    private final com.example.hms.service.pharmacy.PreparedFillVoider preparedFills;
    /** G13: a withdrawal or an edit ends the work-queue claim, as a plain release. */
    private final com.example.hms.service.pharmacy.PrescriptionQueueClaimService queueClaims;
    /** G15 AC-12: readiness on the patient-portal prescriptions read. */
    private final com.example.hms.service.pharmacy.ReadyForCollectionLookup readyForCollection;
    /** D83: an allergy override is audited (who, when, which reason digest). */
    private final AuditEventLogService auditEventLogService;
    /** D414: an inpatient whose stay has no encounter yet gets it opened, never a phantom consultation. */
    private final com.example.hms.repository.AdmissionRepository admissionRepository;
    private final com.example.hms.service.AdmissionStayEncounterService stayEncounters;
    /** D654: a catalog pick carries its code into the order. */
    private final com.example.hms.repository.MedicationCatalogItemRepository medicationCatalogItemRepository;
    /** Round 18: the catalogue flag makes an order controlled, whatever the tick (D922). */
    private final com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;
    /** Round 18: the prescriber's step-up re-authentication at a controlled signature. */
    private final PrescriberReauthenticationService prescriberReauthentication;

    @Override
    @Transactional
    public PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request, Locale locale) {
        rejectClientAssertedWorkflowStatus(request);
        UUID currentUserId = authService.getCurrentUserId();
        // QA D85: the new order belongs to the hospital the caller acts in. It
        // used to land at the home hospital of the caller's FIRST staff row, so
        // a doctor working in two hospitals wrote prescriptions the acting
        // hospital's chart and record PDF never showed.
        UUID actingHospitalId = roleValidator.requireActiveHospitalId();
        Patient patient = resolvePatient(request, locale);

        Staff staff = resolveStaffContext(request, currentUserId, actingHospitalId);
        Encounter encounter = resolveEncounterContext(request, patient, staff, actingHospitalId, null);

        ensureContextConsistency(patient, staff, encounter);

        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }
        if (actingHospitalId != null && !actingHospitalId.equals(hospitalId)) {
            throw new ResourceNotFoundException("encounter.notfound", encounter.getId());
        }
        // QA D401: the order is written into the chart at hospitalId, so the
        // patient must be registered there. Thrown inside the transaction, it
        // also rolls back an encounter resolveEncounterContext auto-created.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospitalId);

        if (!roleValidator.canCreatePrescription(currentUserId, hospitalId)) {
            throw new BusinessException("prescription.only.doctor.admin");
        }
        requirePrescribingRole(staff, hospitalId);

        UserRoleHospitalAssignment prescriberAssignment =
            resolvePrescriberAssignmentOrThrow(staff, encounter, hospitalId);

        // DECISION SUPPORT: Check allergies before prescribing
        String allergyOverride = checkAllergyConflicts(patient, request);

        com.example.hms.model.medication.MedicationCatalogItem catalogItem =
            applyCatalogPick(request, hospitalId, false);

        // CDS rule engine: drug-drug, duplicate-order, pediatric-dose
        List<CdsCard> advisories = withCrossReactivityAdvisory(patient, request.getMedicationName(),
            runCdsRuleEngine(patient, hospitalId, request, catalogItem, null));

        Prescription entity = prescriptionMapper.toEntity(request, patient, staff, encounter);
        entity.setAssignment(prescriberAssignment);
        requirePrescribedQuantityForControlled(entity, catalogItem);
        recordAllergyOverride(entity, allergyOverride);
        String cdsOverride = cdsOverrideReason(request, advisories);
        recordCdsOverride(entity, cdsOverride);
        controlledSubstanceGuard.requireSafeguardsFor(entity, entity.getStatus());

        Prescription saved = prescriptionRepository.save(entity);
        if (allergyOverride != null) {
            auditAllergyOverrideAfterCommit(saved, currentUserId, allergyOverride, "Allergy override recorded at creation");
        }
        if (cdsOverride != null) {
            auditCdsOverrideAfterCommit(saved, currentUserId, cdsOverride, advisories,
                "Critical decision-support alert overridden at creation");
        }
        PrescriptionResponseDTO response = prescriptionMapper.toResponseDTO(saved);
        response.setCdsAdvisories(advisories);
        return response;
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO getPrescriptionById(UUID id, Locale locale) {
        if (subjectReadGuard.isPatientOnly(PrescriptionReaderRoles.CLINICAL_READER_ROLES)) {
            // A patient principal is bounded by ownership, not by a hospital,
            // as the encounter reads are: their own prescriptions follow them
            // across tenants, so no scope is resolved and a patient whose
            // hospital cannot be resolved still reads their own.
            Prescription prescription = findPrescriptionOrNotFound(id);
            if (!readableAsItsPatient(prescription)) {
                throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
            }
            return prescriptionMapper.toResponseDTO(prescription).withoutClarificationExchange();
        }
        // Scope first, before the row is read: a caller refused for having no
        // hospital must get the same answer whether or not the id is real.
        UUID hospitalId = resolvePrescriptionReadScope();
        Prescription prescription = findPrescriptionOrNotFound(id);
        if (hospitalId == null || isAtHospital(prescription, hospitalId)) {
            // Staff access: the clinical copy, clarification exchange included.
            return prescriptionMapper.toResponseDTO(prescription);
        }
        if (readableAcrossHospitals(prescription, hospitalId)) {
            // QA D652: the by-id read answers as the by-patient list does (the
            // rule D508 gave imaging): another hospital's prescription the list
            // shows is readable here; one it never sent is not, in either.
            return prescriptionMapper.toResponseDTO(prescription);
        }
        if (prescription.getHospital() != null && subjectReadGuard.ownsAsItsPatient(prescription.getPatient())) {
            // Staff who are also patients: the fallback #754 gave the
            // encounter reads. A nurse at hospital A who was a patient at
            // hospital B is a clinical reader, so she is held to A, which
            // refused her her OWN prescription from B on an endpoint that
            // admits ROLE_PATIENT. Owning it opens it wherever she works, but
            // only with the patient grant: a staff account linked to a patient
            // row whose ROLE_PATIENT was never granted, or was revoked, must
            // not read that row across hospitals through its clinical role.
            //
            // She is here as its PATIENT, not as B's staff, so she gets the
            // patient copy: hospital B's pharmacist-to-prescriber exchange is
            // not hers to read. The controller strips it only for patient-only
            // callers, which she is not, so the service strips it here.
            //
            // The super-admin exception ReaderRolePredicates.holdsPatientRole
            // documents, weighed for this caller: an expanded super-admin
            // holds ROLE_PATIENT, and gains through this only prescriptions
            // their OWN account owns at a hospital other than the one they
            // pinned, as a patient copy; an unverified one never gets here
            // (resolvePrescriptionReadScope). resolve-clarification also reads
            // through here and is ROLE_DOCTOR-only; for it the fallback can
            // only turn a 404 on the read-back of the doctor's own
            // prescription into its patient copy.
            return prescriptionMapper.toResponseDTO(prescription).withoutClarificationExchange();
        }
        throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
    }

    /** Statuses of a prescription never sent: its writing hospital's work in progress (QA D101, D652). */
    static final List<PrescriptionStatus> NEVER_SENT = NeverSentRule.NEVER_SENT_PRESCRIPTION;

    static boolean wasSent(Prescription prescription) {
        return NeverSentRule.sent(prescription.getStatus());
    }

    /**
     * QA D652 - a foreign prescription is readable by id exactly when the
     * by-patient list would show it: its hospital is in the readable set for
     * this caller and patient, and it was sent ({@link NeverSentRule}).
     * Accounted as a cross-hospital read, as the list accounts it.
     */
    private boolean readableAcrossHospitals(Prescription prescription, UUID hospitalId) {
        UUID rowHospitalId = prescription.getHospital() != null ? prescription.getHospital().getId() : null;
        UUID patientId = prescription.getPatient() != null ? prescription.getPatient().getId() : null;
        if (rowHospitalId == null || patientId == null
                || !NeverSentRule.readableHere(rowHospitalId, hospitalId, wasSent(prescription))) {
            return false;
        }
        UUID requesterUserId = authService.getCurrentUserId();
        if (!recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId).contains(rowHospitalId)) {
            return false;
        }
        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(List.of(rowHospitalId), hospitalId),
            CROSS_HOSPITAL_READ);
        return true;
    }

    private Prescription findPrescriptionOrNotFound(UUID id) {
        return prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
    }

    /**
     * The hospital a staff by-id read is bounded by, or {@code null} for a
     * verified super-admin in global view: the rule #746 gave the encounter
     * reads. Not consulted for a patient-only caller, who is bounded by
     * ownership instead.
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
     * Is this prescription at {@code hospitalId}? A prescription with no
     * hospital is at none: see {@link #readableAsItsPatient} for the one rule
     * such a row follows.
     */
    private static boolean isAtHospital(Prescription prescription, UUID hospitalId) {
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
     * May the caller read this prescription as its patient? The one test both
     * patient roads apply: a patient-only principal, and a staff member who
     * holds {@code ROLE_PATIENT} reading outside her hospital.
     *
     * <p>A patient may read their own prescription and no one else's. The
     * hospital scope was once the only check on a patient; it bounded the leak
     * to the hospital their assignment resolved to, but within it any
     * prescription id returned somebody else's medication, dose, frequency,
     * duration and instructions.
     *
     * <p><b>A prescription with no hospital is read as its patient by no
     * one.</b> A row we cannot place is exactly the one not to hand out, and
     * that holds on both patient roads as it does on the staff road
     * ({@link #isAtHospital}). Only a verified super-admin in global view,
     * who reads every row, is unaffected. {@code Prescription.hospital} is
     * {@code nullable = false}, so no stored row reaches the branch; it is there
     * so an unsaved entity or a future nullable column cannot become readable
     * by ownership alone.
     *
     * <p>A {@code false} is answered 404, not 403, with the same message as a
     * prescription that does not exist: the answer must not tell a patient
     * that an id is real — including when the principal itself cannot be
     * resolved to a patient row, and without a data defect turning the
     * refusal into a stack trace. The subject comes from the authenticated
     * principal ({@code user id → Patient}), never from anything in the
     * request — the rule {@code PatientPortalServiceImpl.resolvePatientId}
     * already follows for every {@code /me/patient/*} read, through the same
     * resolver.
     *
     * <p>Who counts as patient-only is {@link PrescriptionReaderRoles}, which
     * mirrors the endpoint's own annotation and records what that costs on the
     * OIDC path. The write endpoints that admit roles this read does not go
     * through {@link #getPrescriptionAfterWrite} instead.
     */
    private boolean readableAsItsPatient(Prescription prescription) {
        return prescription.getHospital() != null && subjectReadGuard.callerOwns(prescription.getPatient());
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
        return signPrescription(id, null, locale);
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO signPrescription(UUID id,
                                                    com.example.hms.payload.dto.SignPrescriptionRequestDTO reauthentication,
                                                    Locale locale) {
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
            throw new BusinessException("prescription.sign.alreadySigned");
        }

        PrescriptionStatus status = prescription.getStatus();
        if (status != null && status != PrescriptionStatus.DRAFT
                && status != PrescriptionStatus.PENDING_SIGNATURE) {
            throw new BusinessException("prescription.sign.wrongStatus", status.localizedLabel());
        }

        Staff signer = resolveSigningPrescriber(prescription);
        // Review of #975 (D934): a doctor may now sign a nurse's or a
        // midwife's draft, so the clinician who co-signed it must not also be
        // the one who signs it - two signatures, one judgment.
        if (sameClinician(prescription.getCosignedBy(), signer)) {
            throw new BusinessException("prescription.sign.ownCosign");
        }

        // Round 18: a controlled drug (the prescriber's tick OR the catalogue's
        // flag, D922) is signed only after the prescriber re-authenticates on
        // this very call (EPCS-style step-up). The proof is written on the
        // order as its two-factor verification, for this signature only; the
        // tick-keyed safeguard below then has the step it waited for.
        if (controlledResolver.isControlled(prescription)) {
            controlledSubstanceGuard.requireCosignedFor(prescription, PrescriptionStatus.SIGNED);
            UUID signerUserId = signer.getUser() != null ? signer.getUser().getId() : null;
            PrescriberReauthenticationService.Verified verified =
                prescriberReauthentication.requireForControlledSigning(signerUserId, prescription.getId(),
                    reauthentication == null ? null : reauthentication.getPassword(),
                    reauthentication == null ? null : reauthentication.getMfaCode());
            prescription.setTwoFactorVerifiedAt(verified.at());
            prescription.setTwoFactorMethod(verified.method());
        }

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
        PrescriptionResponseDTO signed = prescriptionMapper.toResponseDTO(prescriptionRepository.save(prescription));
        // QA D793: the signature is the last look before the pharmacy and the
        // ward act on the order, so the cross-reactivity advisory is repeated
        // here (it never blocks the signature).
        List<CdsCard> advisories = withCrossReactivityAdvisory(prescription.getPatient(),
            prescription.getMedicationName(), List.of());
        if (!advisories.isEmpty()) {
            signed.setCdsAdvisories(advisories);
        }
        return signed;
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
            // QA D934: in the reader's language.
            throw new BusinessException("prescription.cosign.notRequired");
        }
        if (prescription.getCosignedAt() != null || prescription.getCosignedBy() != null) {
            throw new BusinessException("prescription.cosign.alreadyCosigned");
        }

        PrescriptionStatus status = prescription.getStatus();
        if (status != null && status != PrescriptionStatus.DRAFT
                && status != PrescriptionStatus.PENDING_SIGNATURE) {
            throw new BusinessException("prescription.cosign.wrongStatus", status.localizedLabel());
        }

        Staff prescriber = prescription.getStaff();
        if (prescriber != null && prescriber.getId() != null
                && prescriber.getId().equals(cosigner.getId())) {
            throw new BusinessException("prescription.cosign.ownPrescriber");
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
        // Client-safe: cosignPrescription has already answered 404 for a
        // prescription outside the caller's scope, and the sentence names what
        // the caller would need, not anything about the record.
        if (!prescriberHere) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.cosign.prescriberOnly"));
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId)
            .orElseThrow(() -> new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.cosign.staffProfileRequired")));
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
        if (currentUserId != null && prescriberUserId != null && prescriberUserId.equals(currentUserId)) {
            return prescriber;
        }
        // QA D934: a nurse or a midwife may draft a prescription (D611/D677)
        // but holds no signature, so her order could never be signed by anyone
        // and never reached the pharmacy. A doctor of the order's hospital signs
        // it: the drafter stays the prescriber of record, the doctor is the
        // signer. A doctor's own draft is still signed by that doctor only.
        UUID rxHospitalId = prescription.getHospital() != null ? prescription.getHospital().getId() : null;
        if (currentUserId != null && prescriberUserId != null && rxHospitalId != null
                && roleValidator.draftsForPhysicianSignature(prescriberUserId, rxHospitalId)
                && roleValidator.isSigningPhysician(currentUserId, rxHospitalId)) {
            return staffRepository.findByUserIdAndHospitalId(currentUserId, rxHospitalId)
                .orElseThrow(() -> new com.example.hms.exception.ClientSafeAccessDeniedException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.sign.staffProfileRequired")));
        }
        // Client-safe: signPrescription resolved the row in the caller's
        // scope first; "you are not its prescriber" reveals nothing more.
        throw new com.example.hms.exception.ClientSafeAccessDeniedException(
            com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.sign.prescriberOnly"));
    }

    /**
     * QA D930 class: who may rewrite or delete an unsigned prescription. The
     * same people who may sign it ({@link #resolveSigningPrescriber}): its
     * prescriber, or - for a nurse's or midwife's draft (QA D934) - a signing
     * physician of the order's hospital, who corrects what she will sign. A
     * verified super-admin in global view keeps the old reach. A row with no
     * prescriber keeps the old rule (any prescriber in scope). Any other
     * colleague gets 403; the row was already resolved in the caller's scope.
     */
    private void requireDraftAuthor(Prescription prescription) {
        Staff prescriber = prescription.getStaff();
        UUID prescriberUserId = prescriber != null && prescriber.getUser() != null
            ? prescriber.getUser().getId()
            : null;
        if (prescriberUserId == null || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()) {
            return;
        }
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (prescriberUserId.equals(currentUserId)) {
            return;
        }
        UUID rxHospitalId = prescription.getHospital() != null ? prescription.getHospital().getId() : null;
        if (currentUserId != null && rxHospitalId != null
                && roleValidator.draftsForPhysicianSignature(prescriberUserId, rxHospitalId)
                && roleValidator.isSigningPhysician(currentUserId, rxHospitalId)) {
            return;
        }
        throw new com.example.hms.exception.ClientSafeAccessDeniedException(
            com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.draft.authorOnly"));
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
            // QA D660: a key, so the refusal reaches the prescriber in her language.
            throw new BusinessException("prescription.status.notAssertable");
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
            com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.clarification.statusLocked"));
    }

    /**
     * Withdrawal is final. A CANCELLED or DISCONTINUED order can move to the
     * other withdrawn state and nowhere else: reopened (DRAFT is a status a
     * client may assert), its partner offers would act live again — an
     * ACCEPTED offer stays open after withdrawal so a late dispense is
     * surfaced, and the partner holding it has been told not to dispense.
     * A prescriber who wants the medication again writes a new prescription.
     */
    private static void rejectReopeningWithdrawn(PrescriptionStatus requested) {
        if (!requested.isWithdrawn()) {
            throw new BusinessException("prescription.withdrawn.final");
        }
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
     * A withdrawn order takes its partner-pharmacy offers with it: unanswered
     * ones are closed so the timeout sweep and a late reply cannot stamp a
     * partner status back over it, and every partner holding one is told not
     * to dispense (WithdrawnOrderPartnerHandler). Same transaction as the
     * status change; the partner SMS goes out after it commits.
     */
    private void closePartnerOffersOnWithdrawal(Prescription prescription, PrescriptionStatus statusBefore) {
        PrescriptionStatus now = prescription.getStatus();
        // Only on the way INTO withdrawal: CANCELLED -> DISCONTINUED (or back)
        // is still the same withdrawn order, and its partners were already told.
        if (now == null || !now.isWithdrawn() || (statusBefore != null && statusBefore.isWithdrawn())) {
            return;
        }
        withdrawnOrders.withdrawPartnerOffers(prescription);
    }

    /**
     * G15 rule 8 (user decision 1): a withdrawn or changed order can never be
     * collected, so ANY successful edit of a non-withdrawn order voids its
     * open preparation: PRESCRIPTION_WITHDRAWN on the way into withdrawal,
     * PRESCRIPTION_CHANGED otherwise. The stock goes back and the patient is
     * told after commit. Runs under the row lock {@code updatePrescription}
     * took, inside this already-authorised transaction (a global-view
     * super-admin included), so the voider makes no scope call of its own.
     * An order that was already withdrawn cannot hold one: its withdrawal
     * voided it.
     */
    private void voidPreparedFillOnEdit(Prescription prescription, PrescriptionStatus statusBefore) {
        if (statusBefore != null && statusBefore.isWithdrawn()) {
            return;
        }
        PrescriptionStatus now = prescription.getStatus();
        preparedFills.voidPreparedFill(prescription, now != null && now.isWithdrawn()
            ? com.example.hms.enums.ReadyCancelReason.PRESCRIPTION_WITHDRAWN
            : com.example.hms.enums.ReadyCancelReason.PRESCRIPTION_CHANGED);
    }

    /**
     * G13: a prescriber's withdrawal (WITHDRAWN) or any other successful edit
     * (CHANGED) ends the work-queue claim, audited as a RELEASE whoever acts:
     * a prescriber ends the work, they do not take it over. Same transaction
     * and row lock as the edit, so a rolled-back edit keeps the claim; the
     * claim service makes no scope call (a global-view super-admin included).
     */
    private void releaseQueueClaimOnEdit(Prescription prescription, UUID actorUserId) {
        PrescriptionStatus now = prescription.getStatus();
        queueClaims.releaseOnExit(prescription, now != null && now.isWithdrawn()
                ? com.example.hms.enums.QueueClaimReleaseReason.WITHDRAWN
                : com.example.hms.enums.QueueClaimReleaseReason.CHANGED,
            actorUserId, com.example.hms.enums.QueueClaimExitActor.OTHER);
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
            throw new BusinessException("prescription.controlled.flagLocked");
        }
        if (existing.isRequiresCosign() && Boolean.FALSE.equals(request.getRequiresCosign())) {
            throw new BusinessException("prescription.cosign.flagLocked");
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
        Page<Prescription> rows;
        if (readable.equals(Set.of(hospitalId))) {
            rows = filter.isEmpty()
                ? prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, readable, pageable)
                : prescriptionRepository.findByPatient_IdAndHospital_IdInAndStatusIn(patientId, readable, filter, pageable);
        } else {
            // QA D652: another hospital's never-sent prescription is not listed
            // here, as its by-id read answers 404 here (NeverSentRule).
            rows = filter.isEmpty()
                ? prescriptionRepository.findSentAcrossHospitals(patientId, readable, hospitalId, NEVER_SENT, pageable)
                : prescriptionRepository.findSentAcrossHospitalsAndStatusIn(
                    patientId, readable, hospitalId, NEVER_SENT, filter, pageable);
        }
        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(rows.getContent().stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList(), hospitalId),
            CROSS_HOSPITAL_READ);
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

    /** The same person: the same staff row, or two staff rows of one user account. */
    private static boolean sameClinician(Staff a, Staff b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getId() != null && a.getId().equals(b.getId())) {
            return true;
        }
        return a.getUser() != null && b.getUser() != null && a.getUser().getId() != null
            && a.getUser().getId().equals(b.getUser().getId());
    }

    /**
     * Review of #975: an edit that names the same drug but sends neither id
     * nor code keeps the item the order was written from, BEFORE any
     * resolution runs (the mapper would write the null over the link,
     * dropping the controlled flag and the paediatric ceiling it carries).
     * An edit of a legacy, unlinked order keeping its name is resolved
     * leniently by {@code applyCatalogPick}: one match links it, several
     * leave it as it was. Returns whether the edit names the same drug.
     */
    private static boolean keepCatalogLinkBeforeResolution(Prescription existing, PrescriptionRequestDTO request) {
        boolean sameDrugEdit = !textDiffers(request.getMedicationName(), existing.getMedicationName());
        if (existing.getMedicationCatalogItemId() != null && sameDrugEdit
                && request.getMedicationCatalogItemId() == null && isBlank(request.getMedicationCode())) {
            request.setMedicationCatalogItemId(existing.getMedicationCatalogItemId());
        }
        return sameDrugEdit;
    }

    /**
     * Review of #975: a code that resolves to nothing, then several name
     * matches, must not unlink a same-drug edit either: whatever path left
     * the request without an id after {@code applyCatalogPick}, the order
     * keeps the item it had.
     */
    private com.example.hms.model.medication.MedicationCatalogItem keepCatalogLinkAfterResolution(
            Prescription existing, PrescriptionRequestDTO request, UUID hospitalId, boolean sameDrugEdit,
            com.example.hms.model.medication.MedicationCatalogItem resolved) {
        UUID linkedItemId = existing.getMedicationCatalogItemId();
        if (linkedItemId == null || !sameDrugEdit || request.getMedicationCatalogItemId() != null) {
            return resolved;
        }
        request.setMedicationCatalogItemId(linkedItemId);
        return medicationCatalogItemRepository == null ? resolved : medicationCatalogItemRepository
            .findByIdAndHospital_Id(linkedItemId, hospitalId)
            .orElse(resolved);
    }

    /**
     * QA D706: a controlled substance states how much may be handed over,
     * on every path that creates one (the form, order sets), because
     * the pharmacy caps the dispense at it and refuses one without it.
     * QA D922 (user decision 2026-10-28, supersedes D654): a drug the
     * catalogue marks controlled is controlled whatever the prescriber
     * ticked, so the catalogue item decides too.
     */
    private static void requirePrescribedQuantityForControlled(
            Prescription prescription, com.example.hms.model.medication.MedicationCatalogItem catalogItem) {
        boolean controlled = prescription.isControlledSubstance() || catalogItem != null && catalogItem.isControlled();
        if (controlled && prescription.getQuantity() == null) {
            throw new BusinessException("prescription.quantity.requiredControlled");
        }
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO updatePrescription(UUID id, PrescriptionRequestDTO request, Locale locale) {
        // Locked (G15 rule 1): a withdrawal or an edit voids an open
        // preparation, and a preparation never writes this row, so @Version
        // cannot see the race; the row lock serialises the two.
        // The hospital is in the locking query when one is pinned (#825
        // security finding 3); a super-admin in global view has none.
        UUID actingHospitalId = roleValidator.requireActiveHospitalId();
        Prescription existing = (actingHospitalId != null
                ? prescriptionRepository.findByIdAndHospitalIdForUpdate(id, actingHospitalId)
                : prescriptionRepository.findByIdForUpdate(id))
            .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        // ── Hospital scope enforcement ── the row's own hospital, as on
        // sign/co-sign/delete, and BEFORE the status checks below, which
        // answer 400 and would otherwise tell a real id from a missing one.
        // Authority used to be judged only at the REQUEST's encounter
        // hospital, so another hospital's prescription could be rewritten.
        // Kept after the scoped lock as defence in depth.
        if (actingHospitalId != null
                && (existing.getHospital() == null || !actingHospitalId.equals(existing.getHospital().getId()))) {
            throw new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND);
        }
        // QA D660: a signed order is no longer rewritten by edit.
        if (existing.getStatus() == PrescriptionStatus.SIGNED) {
            return withdrawSignedPrescription(existing, request);
        }
        // QA D660 (re-test 2): a withdrawn order is read-only, every field.
        // It used to fall through to updateEntity below, so a signed order
        // once withdrawn had its drug and dose rewritten under the signature.
        if (existing.getStatus() != null && existing.getStatus().isWithdrawn()) {
            rejectAnyEditOfWithdrawn(request);
        }
        // QA D930 class: an unsigned prescription is its prescriber's work in
        // progress; a colleague at the same hospital reads it but does not
        // rewrite it. Checked after the hospital scope (404) and before the
        // status checks (400), so a stranger learns nothing about the row.
        requireDraftAuthor(existing);
        rejectStatusChangeOnUpdate(existing, request);
        rejectSafeguardWithdrawal(existing, request);

        UUID currentUserId = authService.getCurrentUserId();

        Patient patient = resolvePatient(request, locale);

        Staff staff = resolveStaffContext(request, currentUserId, actingHospitalId);
        Encounter encounter = resolveEncounterContext(request, patient, staff, actingHospitalId,
            existing.getEncounter() == null ? null : existing.getEncounter().getId());

        ensureContextConsistency(patient, staff, encounter);

        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }
        // Never moved onto another hospital's encounter: the row stays where
        // the caller acts. An encounter elsewhere answers exactly as a missing
        // one. A super-admin in global view keeps the old behaviour.
        if (actingHospitalId != null && !actingHospitalId.equals(hospitalId)) {
            throw new ResourceNotFoundException("encounter.notfound", encounter.getId());
        }
        // QA D401: the order is written into the chart at hospitalId, so the
        // patient must be registered there. Thrown inside the transaction, it
        // also rolls back an encounter resolveEncounterContext auto-created.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospitalId);

        if (!roleValidator.canCreatePrescription(currentUserId, hospitalId)) {
            throw new BusinessException("prescription.only.doctor.admin");
        }
        // The staff member written as prescriber must be able to prescribe,
        // on update as on create (D28).
        requirePrescribingRole(staff, hospitalId);

        UserRoleHospitalAssignment prescriberAssignment =
            resolvePrescriberAssignmentOrThrow(staff, encounter, hospitalId);

        // DECISION SUPPORT: Check allergies before updating prescription
        String allergyOverride = checkAllergyConflicts(patient, request);

        boolean sameDrugEdit = keepCatalogLinkBeforeResolution(existing, request);
        com.example.hms.model.medication.MedicationCatalogItem catalogItem = keepCatalogLinkAfterResolution(
            existing, request, hospitalId, sameDrugEdit, applyCatalogPick(request, hospitalId, sameDrugEdit));

        // CDS rule engine: drug-drug, duplicate-order, pediatric-dose. QA D970:
        // the order being edited is not a duplicate of itself.
        List<CdsCard> advisories = withCrossReactivityAdvisory(patient, request.getMedicationName(),
            runCdsRuleEngine(patient, hospitalId, request, catalogItem, existing.getId()));

        PrescriptionStatus statusBefore = existing.getStatus();
        String overrideBefore = existing.isAllergiesReviewed() ? existing.getOverrideReason() : null;
        prescriptionMapper.updateEntity(existing, request, patient, staff, encounter);
        existing.setAssignment(prescriberAssignment);
        // QA D706: no quantity check on an edit - a legacy controlled row must
        // stay withdrawable; the dispense refuses it until a quantity is set.
        applyAllergyOverrideOnUpdate(existing, allergyOverride, overrideBefore, currentUserId);
        String cdsOverride = cdsOverrideReason(request, advisories);
        if (!existing.isAllergiesReviewed()) {
            // Round 18: with no allergy override the field is the CDS reason's:
            // an edit writes its own reason, or drops a stale one that vouched
            // for a dose no longer ordered (an allergy reason is the allergy
            // logic's, above, and is never overwritten).
            existing.setOverrideReason(cdsOverride);
        }
        if (cdsOverride != null) {
            auditCdsOverrideAfterCommit(existing, currentUserId, cdsOverride, advisories,
                "Critical decision-support alert overridden on edit");
        }
        closePartnerOffersOnWithdrawal(existing, statusBefore);
        // Tier 2 item 33: an edit invalidates any pharmacist verification.
        // updateEntity rewrites medicationName, dosage and frequency, and
        // there is no status guard above — so a SIGNED prescription's drug
        // and dose can change here. A verification that survived that would
        // assert a check of a drug the pharmacist never saw.
        pharmacistVerificationService.invalidateOnChange(existing);
        controlledSubstanceGuard.requireSafeguardsFor(existing, existing.getStatus());
        voidPreparedFillOnEdit(existing, statusBefore);
        releaseQueueClaimOnEdit(existing, currentUserId);

        Prescription saved = prescriptionRepository.save(existing);
        PrescriptionResponseDTO response = prescriptionMapper.toResponseDTO(saved);
        response.setCdsAdvisories(advisories);
        return response;
    }

    /**
     * QA D660 (user decision): the only edit a SIGNED prescription accepts is
     * its withdrawal, to CANCELLED or DISCONTINUED, with a reason. The signed
     * drug, dose, route, patient, prescriber and encounter are what the
     * signature digest covers; rewriting them under the same signature is
     * what the edit form used to allow. The portal echoes the whole form, so
     * a field that equals the stored value (or is absent) is not a change;
     * any other value is refused before anything is written.
     */
    private PrescriptionResponseDTO withdrawSignedPrescription(Prescription existing,
                                                               PrescriptionRequestDTO request) {
        PrescriptionStatus requested = request.getStatus();
        if (requested == null || !requested.isWithdrawn() || changesSignedContent(existing, request)) {
            throw new BusinessException("prescription.signed.onlyWithdrawal");
        }
        String reason = request.getWithdrawalReason() == null ? null : request.getWithdrawalReason().trim();
        if (reason == null || reason.isEmpty()) {
            throw new BusinessException("prescription.signed.withdrawalReason.required");
        }
        UUID currentUserId = authService.getCurrentUserId();
        UUID hospitalId = existing.getHospital() != null ? existing.getHospital().getId() : null;
        requireMayWithdrawSigned(existing, currentUserId, hospitalId);
        PrescriptionStatus statusBefore = existing.getStatus();
        existing.setStatus(requested);
        existing.setWithdrawalReason(reason);
        closePartnerOffersOnWithdrawal(existing, statusBefore);
        voidPreparedFillOnEdit(existing, statusBefore);
        releaseQueueClaimOnEdit(existing, currentUserId);
        return prescriptionMapper.toResponseDTO(prescriptionRepository.save(existing));
    }

    /**
     * QA D660 (re-test 2): a CANCELLED or DISCONTINUED order is final and
     * read-only. Asking for a non-withdrawn status keeps the "cannot be
     * reopened" answer; any other edit, its content or its status, is refused.
     */
    private static void rejectAnyEditOfWithdrawn(PrescriptionRequestDTO request) {
        PrescriptionStatus requested = request.getStatus();
        if (requested != null && !requested.isWithdrawn()) {
            rejectReopeningWithdrawn(requested);
        }
        throw new BusinessException("prescription.withdrawn.readOnly");
    }

    /**
     * QA D670 (coordinator decision): a signed order is withdrawn by the
     * prescriber or another doctor of the same hospital. A nurse or a midwife
     * may write a prescription but not withdraw a signed one; the refusal is
     * a translated 403. Admins never reach PUT /prescriptions (E9 #67,
     * HospitalAdminOffOrdersTest), so they have no arm here.
     */
    private void requireMayWithdrawSigned(Prescription existing, UUID callerId, UUID hospitalId) {
        if (hospitalId == null || callerId == null) {
            throw new BusinessException("prescription.only.doctor.admin");
        }
        boolean isPrescriber = existing.getStaff() != null && existing.getStaff().getUser() != null
            && callerId.equals(existing.getStaff().getUser().getId());
        boolean allowed = isPrescriber
            || roleValidator.isDoctor(callerId, hospitalId)
            || roleValidator.isPhysician(callerId, hospitalId)
            || roleValidator.isSurgeon(callerId, hospitalId);
        if (!allowed) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.signed.withdraw.forbidden"));
        }
    }

    /** True when the request asks for anything on a signed order other than its status. */
    private static boolean changesSignedContent(Prescription existing, PrescriptionRequestDTO request) {
        java.util.List<Boolean> changes = java.util.List.of(
            differs(request.getPatientId(), existing.getPatient() == null ? null : existing.getPatient().getId()),
            request.getPatientId() == null && !isBlank(request.getPatientIdentifier()),
            differs(request.getStaffId(), existing.getStaff() == null ? null : existing.getStaff().getId()),
            differs(request.getEncounterId(), existing.getEncounter() == null ? null : existing.getEncounter().getId()),
            textDiffers(request.getMedicationName(), existing.getMedicationName()),
            !isBlank(request.getMedicationCode()) && textDiffers(request.getMedicationCode(), existing.getMedicationCode()),
            textDiffers(request.getDosage(), existing.getDosage()),
            textDiffers(request.getFrequency(), existing.getFrequency()),
            // QA D950: "IV" echoed over a stored "INTRAVENOUS" is the same route.
            !isBlank(request.getRoute())
                && !com.example.hms.service.emar.MedicationRoutes.sameRoute(existing.getRoute(), request.getRoute()),
            textDiffers(request.getDuration(), existing.getDuration()),
            textDiffers(request.getNotes(), existing.getNotes()),
            flagDiffers(request.getControlledSubstance(), existing.isControlledSubstance()),
            flagDiffers(request.getRequiresCosign(), existing.isRequiresCosign()),
            flagDiffers(request.getTakeHome(), existing.isTakeHome()),
            Boolean.TRUE.equals(request.getForceOverride()));
        return changes.contains(Boolean.TRUE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean flagDiffers(Boolean requested, boolean stored) {
        return requested != null && requested != stored;
    }

    private static boolean differs(UUID requested, UUID stored) {
        return requested != null && !requested.equals(stored);
    }

    /** Blank and absent are the same "nothing recorded" the form echoes. */
    private static boolean textDiffers(String requested, String stored) {
        if (requested == null) {
            return false;
        }
        String r = requested.trim();
        String st = stored == null ? "" : stored.trim();
        return !r.equals(st);
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
            // QA D652: less another hospital's never-sent prescriptions (NeverSentRule).
            List<Prescription> rows = NeverSentRule.prescriptionsReadableHere(
                prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, readable), hospitalId);
            reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(rows.stream().map(p -> CrossHospitalReachRecorder.hospitalIdOf(p.getHospital())).toList(), hospitalId),
                CROSS_HOSPITAL_READ);
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
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsForPortalPatient(UUID patientId, Locale locale) {
        // QA D101: an unsigned prescription (DRAFT, PENDING_SIGNATURE) is the prescriber's
        // work in progress, not something issued to the patient.
        java.util.List<Prescription> rows = prescriptionRepository.findByPatient_Id(patientId, Pageable.unpaged())
            .getContent().stream()
            .filter(p -> p.getStatus() != com.example.hms.enums.PrescriptionStatus.DRAFT
                && p.getStatus() != com.example.hms.enums.PrescriptionStatus.PENDING_SIGNATURE)
            .toList();
        // G15 AC-12: a fill waiting at the counter, one query for the list.
        java.util.Map<UUID, com.example.hms.service.pharmacy.ReadyForCollectionLookup.Readiness> ready =
            readyForCollection.openPreparations(rows);
        return rows.stream()
            .map(p -> {
                PrescriptionResponseDTO dto = prescriptionMapper.toResponseDTO(p);
                var waiting = ready.get(p.getId());
                if (dto != null && waiting != null) {
                    dto.setReadyForCollectionAt(waiting.readyAt());
                    dto.setReadyForCollectionPharmacyName(waiting.pharmacyName());
                }
                return dto;
            })
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
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", request.getPatientId()));
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
                    .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", parsed));
            } catch (IllegalArgumentException ignore) {
                // not a UUID, fall through
            }

            throw new ResourceNotFoundException("patient.notFoundByIdentifier", identifier);
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
        // QA D414: an inpatient stay is led by one doctor but ordered on by
        // several (consultants, the doctor on call). Only a visit is the
        // prescriber's own; the prescription carries its prescriber either way,
        // and the hospital check below still pins them to the stay's hospital.
        if (!isPrescribersEncounter(encounter, staff)) {
            if (encounter.getAdmissionId() == null) {
                throw new BusinessException("prescription.encounter.staff.mismatch");
            }
            // The waiver covers the stay's care team only: an unrelated doctor
            // at the same hospital may not attach an order to someone's stay.
            requireStayCareTeam(encounter, staff);
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
        // The encounter's assignment is the prescriber's only when the encounter
        // is theirs: on a stay led by another doctor (D414) it is the lead's.
        if (encounter != null && encounter.getAssignment() != null && isPrescribersEncounter(encounter, staff)) {
            return encounter.getAssignment();
        }
        return resolveAssignmentForStaff(staff, hospitalId);
    }

    private static boolean isPrescribersEncounter(Encounter encounter, Staff staff) {
        return encounter.getStaff() != null && staff != null
            && encounter.getStaff().getId() != null && encounter.getStaff().getId().equals(staff.getId());
    }

    /**
     * The prescriber named on the order — {@code request.staffId} when the
     * caller picks one — must hold a prescribing role at the order's hospital.
     * Checking only the caller let a doctor attribute a prescription to a
     * receptionist, whose own staff assignment then became the prescriber of
     * record (QA D28).
     */
    private void requirePrescribingRole(Staff staff, UUID hospitalId) {
        UUID prescriberUserId = staff.getUser() != null ? staff.getUser().getId() : null;
        if (prescriberUserId == null) {
            throw new BusinessException("prescription.assignment.missing.staff.user");
        }
        if (!roleValidator.canCreatePrescription(prescriberUserId, hospitalId)) {
            throw new BusinessException("prescription.prescriber.role.required");
        }
    }

    private Staff resolveStaffContext(PrescriptionRequestDTO request, UUID currentUserId, UUID actingHospitalId) {
        if (request.getStaffId() != null) {
            Staff named = staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("staff.notfound", request.getStaffId()));
            // QA D84: the prescriber of record is the signed-in prescriber; an
            // order cannot be written under a colleague's (or a nurse's) name.
            UUID namedUserId = named.getUser() != null ? named.getUser().getId() : null;
            if (currentUserId != null && namedUserId != null && !currentUserId.equals(namedUserId)) {
                throw new BusinessException("prescription.prescriber.must.be.self");
            }
            return named;
        }
        if (currentUserId == null) {
            throw new BusinessException("prescription.staff.context.missing");
        }
        if (actingHospitalId != null) {
            Optional<Staff> here = staffRepository.findByUserIdAndHospitalId(currentUserId, actingHospitalId);
            if (here.isPresent()) {
                return here.get();
            }
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId)
            .orElseThrow(() -> new BusinessException("prescription.staff.context.missing"));
    }

    private Encounter resolveEncounterContext(PrescriptionRequestDTO request,
                                              Patient patient,
                                              Staff staff,
                                              UUID actingHospitalId,
                                              UUID keptEncounterId) {
        if (request.getEncounterId() != null) {
            Encounter named = encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", request.getEncounterId()));
            // QA D525 (D431): an order written into a hospital stay after the
            // discharge closed it was accepted, and would surface as a due
            // dose for a patient who has gone home.
            if (isHospitalStay(named) && isClosedEncounter(named)) {
                throw new BusinessException("prescription.encounter.stayClosed");
            }
            // QA D739 (r10 re-test 3): the same rule for every encounter type.
            // A finished consultation or ED visit named explicitly took the new
            // order, which then showed in that closed visit's chart. An edit
            // that keeps the order's own encounter is not a new attachment.
            if (isClosedEncounter(named) && !named.getId().equals(keptEncounterId)) {
                throw new BusinessException("prescription.encounter.closed");
            }
            return named;
        }

        UUID hospitalId = actingHospitalId != null ? actingHospitalId : determineHospitalId(staff, patient);
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        // QA D136/D151: an order for an inpatient by the clinician leading the
        // stay belongs to the stay (an encounter carries one prescriber). Filed
        // there, it stops with the discharge (the MAR and the patient's list
        // read the stay's status) and it does not mint a phantom consultation.
        Optional<Encounter> stay = encounterRepository
            .findFirstByPatient_IdAndStaff_IdAndHospital_IdAndAdmissionIdIsNotNullAndStatusNotInOrderByEncounterDateDesc(
                patient.getId(), staff.getId(), hospitalId,
                EncounterStatus.CLOSED);
        if (stay.isPresent()) {
            return stay.get();
        }
        // QA D414 (D41/D97 not fixed): the lookup above only finds a stay the
        // prescriber leads, so a consultant or covering doctor prescribing for
        // an inpatient minted a completed "consultation" that never happened.
        // The order belongs to the patient's open stay whoever leads it; the
        // prescription carries its own prescriber.
        Optional<Encounter> anyStay = encounterRepository
            .findFirstByPatient_IdAndHospital_IdAndAdmissionIdIsNotNullAndStatusNotInOrderByEncounterDateDesc(
                patient.getId(), hospitalId,
                EncounterStatus.CLOSED);
        // The caller must be on that stay's care team: ensureContextConsistency
        // refuses anyone else (verification GAP-1) for every path, this one too.
        if (anyStay.isPresent()) {
            return anyStay.get();
        }
        Encounter openedStay = openStayForActiveAdmission(patient.getId(), hospitalId, staff);
        if (openedStay != null) {
            return openedStay;
        }

        Optional<Encounter> existing = encounterRepository
            .findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
                patient.getId(), staff.getId(), hospitalId);
        // QA D739: the prescriber's latest encounter may be a stay that has
        // been discharged (an open one was taken above). An outpatient order
        // filed there reads as a stay order: it is ended by the discharge and
        // shows in the closed stay's chart. It gets an outpatient anchor.
        // QA D739 (r10 re-test 2): nor is a finished outpatient visit reused:
        // an order written after that consultation closed is not part of it,
        // and would show in its chart and summary. Only a still-open visit of
        // this prescriber anchors the order; otherwise it gets its own anchor.
        if (existing.isPresent() && isReusableOutpatientAnchor(existing.get())) {
            return existing.get();
        }
        // QA D739 (r10 self-review round 2): every order gets a fresh anchor.
        // An earlier anchor is completed by design (QA D41) and may be of any
        // age, so reusing it would file this order on an existing finished
        // encounter, which is the defect QA reported.
        UserRoleHospitalAssignment assignment = resolveAssignmentForStaff(staff, hospitalId);
        return createEncounterSnapshot(patient, staff, assignment);
    }

    private static boolean isClosedEncounter(Encounter encounter) {
        return encounter.getStatus() != null && encounter.getStatus().isClosed();
    }

    /**
     * D414: an admission whose stay encounter was never opened (admitted before
     * stays had encounters, or with no lead at the time) still makes the patient
     * an inpatient; the order opens the stay instead of a completed consultation.
     */
    private Encounter openStayForActiveAdmission(UUID patientId, UUID hospitalId, Staff staff) {
        if (admissionRepository == null || stayEncounters == null) {
            return null;
        }
        Optional<com.example.hms.model.Admission> admission = admissionRepository
            .findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId,
                java.util.EnumSet.of(com.example.hms.enums.AdmissionStatus.ACTIVE,
                    com.example.hms.enums.AdmissionStatus.ON_LEAVE,
                    com.example.hms.enums.AdmissionStatus.AWAITING_DISCHARGE))
            .stream()
            .findFirst();
        if (admission.isEmpty()) {
            return null;
        }
        // Checked before the stay is opened: opening another doctor's stay is
        // itself a write an unrelated prescriber may not cause.
        if (!isOnAdmissionCareTeam(admission.get(), staff)) {
            throw new AccessDeniedException(NOT_ON_STAY_CARE_TEAM);
        }
        return stayEncounters.openStayEncounter(admission.get());
    }

    /**
     * QA D414 (verification GAP-1): an order on a stay led by another doctor is
     * allowed only for its care team: the stay lead, the admission's attending
     * or admitting physician, or a consultant recorded on the admission.
     */
    private void requireStayCareTeam(Encounter stay, Staff staff) {
        if (isPrescribersEncounter(stay, staff)) {
            return;
        }
        com.example.hms.model.Admission admission = admissionRepository == null || stay.getAdmissionId() == null
            ? null : admissionRepository.findById(stay.getAdmissionId()).orElse(null);
        if (admission == null || !isOnAdmissionCareTeam(admission, staff)) {
            throw new AccessDeniedException(NOT_ON_STAY_CARE_TEAM);
        }
    }

    /**
     * QA D703: the stay is the hospital's, not the admitting doctor's. Any
     * prescriber of the stay's hospital who treats the patient (the doctor on
     * call at night, a colleague covering the ward) may order on it; the
     * prescribing role itself is enforced on the endpoint. Tenant scoping is
     * kept: a prescriber of another hospital is refused.
     */
    private static boolean isOnAdmissionCareTeam(com.example.hms.model.Admission admission, Staff staff) {
        UUID staffId = staff != null ? staff.getId() : null;
        if (staffId == null) {
            return false;
        }
        if (sameStaff(admission.getAttendingPhysician(), staffId)
            || sameStaff(admission.getAdmittingProvider(), staffId)) {
            return true;
        }
        UUID stayHospital = admission.getHospital() != null ? admission.getHospital().getId() : null;
        UUID staffHospital = staff.getHospital() != null ? staff.getHospital().getId() : null;
        return stayHospital != null && stayHospital.equals(staffHospital);
    }

    /** An open, non-stay encounter: the only kind an unanchored order may join. */
    private static boolean isReusableOutpatientAnchor(Encounter encounter) {
        EncounterStatus status = encounter.getStatus();
        return !isHospitalStay(encounter)
            && (status == null || !status.isClosed());
    }

    /** A stay's encounter: linked to an admission, or an INPATIENT one from before V193 linked them. */
    private static boolean isHospitalStay(Encounter encounter) {
        return encounter.getAdmissionId() != null || encounter.getEncounterType() == EncounterType.INPATIENT;
    }

    private static boolean sameStaff(Staff member, UUID staffId) {
        return member != null && staffId.equals(member.getId());
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
        // Anchor at the hospital the assignment was resolved for, which is the
        // acting hospital when one is pinned, rather than the staff home hospital.
        Hospital hospital = assignment != null && assignment.getHospital() != null
            ? assignment.getHospital() : staff.getHospital();
        if (staff.getHospital() == null || hospital == null) {
            throw new BusinessException("prescription.staff.hospital.missing");
        }
        // The anchor exists only because a prescription row needs an encounter:
        // it is recorded as already finished, in the prescriber's department, so
        // it never surfaces as an open visit on a worklist or tracker with no
        // department (QA D41).
        LocalDateTime now = LocalDateTime.now();
        Encounter encounter = Encounter.builder()
            .patient(patient)
            .staff(staff)
            .hospital(hospital)
            .department(departmentAtHospital(staff, hospital))
            .assignment(assignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(now)
            .checkoutTimestamp(now)
            .status(EncounterStatus.COMPLETED)
            .notes(Encounter.PRESCRIPTION_ANCHOR_NOTE)
            .build();
        encounter.setCode(generateEncounterCode());
        return encounterRepository.save(encounter);
    }

    private static Department departmentAtHospital(Staff staff, Hospital hospital) {
        Department department = staff.getDepartment();
        if (department == null || department.getHospital() == null
            || !department.getHospital().getId().equals(hospital.getId())) {
            return null;
        }
        return department;
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
     * Check patient allergies against the medication being prescribed (QA D83).
     * Any active documented allergy that matches the drug — by name, ignoring
     * accents, or by drug class (a penicillin allergy covers amoxicillin) —
     * blocks the order unless the prescriber overrides it WITH a reason. The
     * reason is stored on the prescription so the override is on record.
     *
     * @return the override reason to record, or {@code null} when no allergy matched
     */
    private String checkAllergyConflicts(Patient patient, PrescriptionRequestDTO request) {
        String medicationName = request.getMedicationName();
        if (medicationName == null || medicationName.isBlank()) {
            return null; // Cannot check without medication name
        }

        // A hard safety stop: an allergy recorded at ANY hospital counts.
        List<PatientAllergy> allergies = patientAllergyRepository.findByPatient_Id(patient.getId());
        String med = normalizeDrugText(medicationName);
        String reason = request.getOverrideReason() != null ? request.getOverrideReason().trim() : "";
        // QA D432: the warning cited whichever matching entry came first, so a
        // moderate entry could hide a severe one for the same allergen. The
        // most severe matching entry is the one the prescriber is shown.
        Optional<PatientAllergy> worst = allergies.stream()
            .filter(PatientAllergy::isActive)
            .filter(allergy -> matchesAllergen(med, allergy.getAllergenDisplay()))
            .max(java.util.Comparator.comparingInt(PrescriptionServiceImpl::allergySeverityRank));
        if (worst.isEmpty()) {
            return null;
        }
        handleAllergyConflict(patient, worst.get(), medicationName, request.getForceOverride(), reason);
        return reason;
    }

    /**
     * QA D793: the allergy check above blocks only the drug and its own class
     * (a penicillin allergy covers amoxicillin), so ceftriaxone for a
     * penicillin-allergic patient was signed and given with no word. A
     * beta-lactam that may cross-react with a documented beta-lactam allergy
     * adds a WARNING card: shown to the prescriber, never a block, never an
     * override to record. An allergy recorded at any hospital counts, as for
     * the block. The card goes first: it is about the patient, the rule
     * engine's cards are about the other orders.
     */
    private List<CdsCard> withCrossReactivityAdvisory(Patient patient, String medicationName, List<CdsCard> advisories) {
        if (patient == null || patient.getId() == null || medicationName == null || medicationName.isBlank()) {
            return advisories;
        }
        Optional<CdsCard> card = com.example.hms.service.allergy.CrossReactivityAdvisory.card(
            medicationName, patientAllergyRepository.findByPatient_Id(patient.getId()));
        if (card.isEmpty()) {
            return advisories;
        }
        List<CdsCard> out = new java.util.ArrayList<>();
        out.add(card.get());
        if (advisories != null) {
            out.addAll(advisories);
        }
        return out;
    }

    /** Higher is worse; an unknown severity ranks above a mild one, below a moderate one. */
    static int allergySeverityRank(PatientAllergy allergy) {
        if (allergy.getSeverity() == null) {
            return 2;
        }
        return switch (allergy.getSeverity()) {
            case LIFE_THREATENING -> 5;
            case SEVERE -> 4;
            case MODERATE -> 3;
            case UNKNOWN -> 2;
            case MILD -> 1;
        };
    }

    private static void recordAllergyOverride(Prescription prescription, String overrideReason) {
        if (overrideReason != null) {
            prescription.setOverrideReason(overrideReason);
            prescription.setAllergiesReviewed(true);
        }
    }

    /**
     * Round 18 (QA D935): the reason the prescriber gave for passing a
     * critical decision-support alert (a paediatric soft stop, a major
     * interaction), or null when no critical alert was overridden.
     * {@link #runCdsRuleEngine} has already refused a critical alert sent
     * without forceOverride and a reason.
     */
    static String cdsOverrideReason(PrescriptionRequestDTO request, List<CdsCard> advisories) {
        if (!Boolean.TRUE.equals(request.getForceOverride()) || advisories == null
                || advisories.stream().noneMatch(c -> c.indicator() == CdsCard.Indicator.CRITICAL)) {
            return null;
        }
        String reason = request.getOverrideReason() == null ? "" : request.getOverrideReason().trim();
        return reason.isEmpty() ? null : reason;
    }

    /** The CDS override reason is stored on the order unless an allergy override already holds the field. */
    private static void recordCdsOverride(Prescription prescription, String reason) {
        if (reason != null && (prescription.getOverrideReason() == null || prescription.getOverrideReason().isBlank())) {
            prescription.setOverrideReason(reason);
        }
    }

    /**
     * Round 18 (QA D935): after commit, ids, a digest of the reason and how
     * many critical alerts were passed. No drug, dose or reason text: the
     * audit row is not encrypted.
     */
    private void auditCdsOverrideAfterCommit(Prescription prescription, UUID actorId, String reason,
                                             List<CdsCard> advisories, String description) {
        UUID prescriptionId = prescription.getId();
        UUID patientId = prescription.getPatient() != null ? prescription.getPatient().getId() : null;
        UUID assignmentId = prescription.getAssignment() != null ? prescription.getAssignment().getId() : null;
        long critical = advisories.stream().filter(c -> c.indicator() == CdsCard.Indicator.CRITICAL).count();
        String details = "{\"reasonSha256\":\"" + computeSignatureDigest(reason) + "\",\"criticalAlerts\":" + critical + "}";
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(com.example.hms.enums.AuditEventType.PRESCRIPTION_CDS_OVERRIDDEN)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .assignmentId(assignmentId)
                    .patientId(patientId)
                    .eventDescription(description)
                    .details(details)
                    .entityType("Prescription")
                    .resourceId(prescriptionId != null ? prescriptionId.toString() : null)
                    .build());
            } catch (RuntimeException ex) {
                logger.warn("CDS override audit not written for prescription {}: {}", prescriptionId, ex.getClass().getSimpleName());
            }
        });
    }

    /**
     * An edit either still conflicts (the new reason is recorded and audited,
     * so a replaced reason leaves a trail) or no longer does (the stale
     * override is cleared - it vouched for a drug that is no longer ordered -
     * and the clearing is audited too).
     */
    private void applyAllergyOverrideOnUpdate(Prescription existing, String allergyOverride,
                                              String overrideBefore, UUID actorId) {
        if (allergyOverride != null) {
            recordAllergyOverride(existing, allergyOverride);
            if (!allergyOverride.equals(overrideBefore)) {
                auditAllergyOverrideAfterCommit(existing, actorId, allergyOverride,
                    overrideBefore != null ? "Allergy override reason replaced on edit" : "Allergy override recorded on edit");
            }
        } else if (overrideBefore != null || existing.isAllergiesReviewed()) {
            existing.setOverrideReason(null);
            existing.setAllergiesReviewed(false);
            auditAllergyOverrideAfterCommit(existing, actorId, null,
                "Allergy override cleared: the edited order no longer conflicts");
        }
    }

    /**
     * After commit, outside the clinical write. Ids and a digest of the
     * reason only: the reason is free text and the audit row is not
     * encrypted, so the digest proves which reason stood without copying it.
     */
    private void auditAllergyOverrideAfterCommit(Prescription prescription, UUID actorId,
                                                 String reason, String description) {
        UUID prescriptionId = prescription.getId();
        UUID patientId = prescription.getPatient() != null ? prescription.getPatient().getId() : null;
        UUID assignmentId = prescription.getAssignment() != null ? prescription.getAssignment().getId() : null;
        String details = reason == null ? "{\"overrideCleared\":true}"
            : "{\"reasonSha256\":\"" + computeSignatureDigest(reason) + "\"}";
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(com.example.hms.enums.AuditEventType.PRESCRIPTION_ALLERGY_OVERRIDDEN)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(actorId)
                    .assignmentId(assignmentId)
                    .patientId(patientId)
                    .eventDescription(description)
                    .details(details)
                    .entityType("Prescription")
                    .resourceId(prescriptionId != null ? prescriptionId.toString() : null)
                    .build());
            } catch (RuntimeException ex) {
                logger.warn("Allergy override audit not written for prescription {}: {}", prescriptionId, ex.getClass().getSimpleName());
            }
        });
    }

    /** Lower-case, accent-free form: "Pénicilline V" and "penicillin" must meet. */
    static String normalizeDrugText(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .trim();
    }

    static boolean matchesAllergen(String med, String allergenDisplay) {
        String allergen = normalizeDrugText(allergenDisplay);
        if (allergen.isEmpty() || med.isEmpty()) {
            return false;
        }
        if (allergen.contains(med) || med.contains(allergen)) {
            return true;
        }
        // "penicillin" / "pénicilline" / "penicilina": the whole -cillin class.
        return allergen.contains("penicil") && (med.contains("cillin") || med.contains("cilin") || med.contains("penicil"));
    }

    private void handleAllergyConflict(Patient patient, PatientAllergy allergy, String medicationName,
                                       Boolean forceOverride, String reason) {
        String severityStr = allergy.getSeverity() != null ? allergy.getSeverity().name() : "UNKNOWN";

        logger.warn("ALLERGY ALERT: patient {} has a documented {} allergy matching the prescribed drug. Override: {}",
                   patient.getId(), severityStr, forceOverride);

        if (!Boolean.TRUE.equals(forceOverride) || reason.isEmpty()) {
            // Sent as a critical CDS card so the prescribing screen shows the
            // alert and offers the override (with its mandatory reason).
            // QA D333: the severity is shown in the reader's language, never as the enum constant.
            String severityLabel = com.example.hms.utility.MessageUtil.resolveOrRaw("cds.enum." + severityStr);
            String message = com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.allergy.conflict",
                allergy.getAllergenDisplay(), severityLabel, medicationName);
            CdsCard card = new CdsCard(message, null, CdsCard.Indicator.CRITICAL,
                new com.example.hms.cdshooks.dto.CdsHookDtos.Source(
                    com.example.hms.service.allergy.CrossReactivityAdvisory.sourceLabel(), null, null),
                null, null, null, UUID.randomUUID().toString());
            throw new CdsCriticalBlockException(message, List.of(card));
        }
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
                                           PrescriptionRequestDTO request,
                                           com.example.hms.model.medication.MedicationCatalogItem catalogItem,
                                           UUID editedPrescriptionId) {
        List<CdsCard> advisories = cdsRuleEngine.evaluateProposedPrescription(
            patient, hospitalId,
            request.getMedicationName(),
            request.getMedicationCode(),
            request.getDosage(),
            request.getFrequency(),
            catalogItem,
            editedPrescriptionId);
        if (advisories.isEmpty()) {
            return advisories;
        }
        // Round 18 (QA D935): a hard stop (a dose above the absolute daily
        // maximum) is refused whatever the override.
        for (CdsCard card : advisories) {
            if (card.blocksOverride()) {
                if (logger.isWarnEnabled()) {
                    logger.warn("CDS hard stop refused prescription: {}", sanitizeLog(card.summary()));
                }
                throw new CdsCriticalBlockException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.cds.hardStop", card.summary()),
                    advisories);
            }
        }
        if (Boolean.TRUE.equals(request.getForceOverride())) {
            // Round 18 (QA D935): an override of a critical alert states its
            // reason, which is stored on the order and audited (as the
            // allergy override always required, QA D83).
            boolean critical = advisories.stream().anyMatch(c -> c.indicator() == CdsCard.Indicator.CRITICAL);
            if (critical && (request.getOverrideReason() == null || request.getOverrideReason().isBlank())) {
                throw new CdsCriticalBlockException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw("prescription.cds.overrideReasonRequired"),
                    advisories);
            }
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


    /**
     * The catalogue item behind the order, at the order's hospital, so that
     * the catalogue's controlled flag and paediatric ceiling apply to it.
     *
     * <ul>
     *   <li>D654: a catalogue pick ({@code medicationCatalogItemId}) must be
     *       this hospital's item; it lends its code to an order sent without
     *       one.</li>
     *   <li>QA D922/D935: an order sent with neither id nor code (the
     *       prescribing form typed the name; any API client may) is resolved
     *       by NAME among this hospital's active items, with the dispense DRUG
     *       check's name rule. One match is the item: its id is written on the
     *       order, so the controlled flag reaches verification, the MAR and
     *       the patient's refill. Several matches are refused when any of them
     *       is controlled (the prescriber must pick the exact item: guessing
     *       would either drop the flag or tie the order to one strength) -
     *       except on an edit of an unlinked order that keeps its name
     *       ({@code lenientAmbiguity}), which stays as it was rather than
     *       becoming uneditable (review of #975). Several uncontrolled
     *       matches leave the order unlinked; the CDS check then uses the
     *       strictest paediatric ceiling among them.</li>
     *   <li>A code no catalogue item carries (an external RxNorm, an order
     *       set's own code) falls through to the name: the code alone must
     *       not let a controlled drug escape the flag.</li>
     * </ul>
     *
     * @return the item the CDS rules evaluate against, or {@code null} to let
     *         the engine resolve one from the code (or none)
     */
    private com.example.hms.model.medication.MedicationCatalogItem applyCatalogPick(
            PrescriptionRequestDTO request, UUID hospitalId, boolean lenientAmbiguity) {
        if (medicationCatalogItemRepository == null) {
            return null;
        }
        UUID itemId = request.getMedicationCatalogItemId();
        if (itemId != null) {
            com.example.hms.model.medication.MedicationCatalogItem item = medicationCatalogItemRepository
                .findByIdAndHospital_Id(itemId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));
            if ((request.getMedicationCode() == null || request.getMedicationCode().isBlank())
                && item.getCode() != null && !item.getCode().isBlank()) {
                request.setMedicationCode(item.getCode());
            }
            return item;
        }
        if (request.getMedicationCode() != null && !request.getMedicationCode().isBlank()) {
            // Review of #975: a code names this hospital's item as well as an
            // id does; the item is written on the order so an edit sending the
            // code alone does not drop the link (the mapper copies the id).
            com.example.hms.model.medication.MedicationCatalogItem coded = medicationCatalogItemRepository
                .findByHospitalIdAndCode(hospitalId, request.getMedicationCode().trim())
                .orElse(null);
            if (coded != null) {
                request.setMedicationCatalogItemId(coded.getId());
                return coded;
            }
        }
        List<com.example.hms.model.medication.MedicationCatalogItem> named =
            catalogItemsNamed(request.getMedicationName(), hospitalId);
        if (named.isEmpty()) {
            return null;
        }
        if (named.size() == 1) {
            request.setMedicationCatalogItemId(named.get(0).getId());
            return named.get(0);
        }
        if (!lenientAmbiguity
                && named.stream().anyMatch(com.example.hms.model.medication.MedicationCatalogItem::isControlled)) {
            throw new BusinessException("prescription.catalog.ambiguousControlled");
        }
        return strictestPediatricCeiling(named);
    }

    /** QA D922: this hospital's active catalogue items the typed name names. */
    private List<com.example.hms.model.medication.MedicationCatalogItem> catalogItemsNamed(
            String medicationName, UUID hospitalId) {
        if (medicationName == null || medicationName.isBlank() || hospitalId == null) {
            return List.of();
        }
        return medicationCatalogItemRepository
            .findByHospital_IdAndActiveTrue(hospitalId, org.springframework.data.domain.Pageable.unpaged())
            .getContent().stream()
            .filter(item -> com.example.hms.service.pharmacy.DispenseVerificationService
                .namesCatalogItem(medicationName, item))
            .toList();
    }

    /** QA D935: the item with the lowest paediatric ceiling on file, else the first. */
    private static com.example.hms.model.medication.MedicationCatalogItem strictestPediatricCeiling(
            List<com.example.hms.model.medication.MedicationCatalogItem> items) {
        com.example.hms.model.medication.MedicationCatalogItem strictest = items.get(0);
        for (com.example.hms.model.medication.MedicationCatalogItem item : items) {
            java.math.BigDecimal ceiling = item.getPediatricMaxDoseMgPerKg();
            java.math.BigDecimal best = strictest.getPediatricMaxDoseMgPerKg();
            if (ceiling != null && (best == null || ceiling.compareTo(best) < 0)) {
                strictest = item;
            }
        }
        return strictest;
    }
}
