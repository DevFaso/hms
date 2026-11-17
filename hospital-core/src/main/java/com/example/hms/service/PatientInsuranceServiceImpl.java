package com.example.hms.service;

import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.security.ActingContext;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LinkPatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.service.billing.PayerCatalogueService;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class PatientInsuranceServiceImpl implements PatientInsuranceService {
    private static final String PATIENT_REQUIRED_KEY = "patientinsurance.patient.required";
    private static final String ROLE_PATIENT = "PATIENT";
    /** PatientChartAccess's key for a patient id that matches no row (the list read's miss). */
    private static final String CHART_PATIENT_NOT_FOUND_KEY = "patient.notFound";
    private static final String HOSPITAL_REQUIRED_KEY = "hospital.required";
    private static final String INSURANCE_LINK_FORBIDDEN_KEY = "insurance.link.forbidden";
    private static final String INSURANCE_LINK_FORBIDDEN_MSG = "You don't have permission to link insurance in this hospital";
    private static final String ASSIGNMENT_REQUIRED_KEY = "assignment.required";


    private final PatientInsuranceRepository patientInsuranceRepository;
    private final PatientRepository patientRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientInsuranceMapper patientInsuranceMapper;
    private final MessageSource messageSource;
    private final RoleValidator roleValidator;
    private final PatientChartAccess patientChartAccess;
    /**
     * Who the caller is and whether a patient row is theirs: the one ownership
     * check the patient-subject reads share. It resolves the caller through
     * {@code ControllerAuthUtils} (the {@code appUserId} claim on a Keycloak
     * token), where {@code RoleValidator.getCurrentUserId()} is null on one and
     * refused a Keycloak patient their own insurance.
     */
    private final PatientSubjectReadGuard subjectReadGuard;
    private final ActingScopeResolver actingScopeResolver;
    /** Billing-money plan PR 6: the payer catalogue, which says whether a plan may be linked at a hospital. */
    private final PayerCatalogueService payerCatalogueService;

    @Override
    @Transactional
    public PatientInsuranceResponseDTO addInsuranceToPatient(PatientInsuranceRequestDTO dto, Locale locale) {
        if (dto.getPatientId() == null) {
            throw new FieldBusinessException("patientId", PATIENT_REQUIRED_KEY);
        }

        Patient patient = getPatientOrThrow(dto.getPatientId());
        // PATIENT may only act on self; another patient answers as a missing one
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(dto.getPatientId()));

        PatientInsurance insurance = patientInsuranceMapper.toPatientInsurance(dto, patient);

        // Do NOT stamp assignment during create
        insurance.setAssignment(null);

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientInsuranceResponseDTO getPatientInsuranceById(UUID insuranceId, Locale locale) {
        // QA D1651: another hospital's row answers exactly as a missing id
        // does, like an edit or a delete; another patient's too.
        PatientInsurance insurance = getScopedInsuranceOrThrow(insuranceId);
        enforceSelfAccessIfPatient(insurance.getPatient(), () -> insuranceNotFound(insuranceId));
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientInsuranceResponseDTO> getInsurancesByPatientId(UUID patientId, Locale locale) {
        // A patient-only caller may name only their own row, and is told so
        // exactly as an unknown id is (PatientChartAccess's answer), BEFORE the
        // chart lookup, whose answers could otherwise tell the two apart.
        if (roleValidator.isPatientOnlyFromAuth() && !subjectReadGuard.ownsPatientRow(patientId)) {
            throw new ResourceNotFoundException(CHART_PATIENT_NOT_FOUND_KEY, patientId);
        }
        getPatientScoped(patientId);

        // QA D1651: the acting hospital's coverage plus legacy rows tagged to
        // no hospital; a patient-only caller sees all of their own.
        UUID hospitalId = roleValidator.isPatientOnlyFromAuth() ? null : roleValidator.requireActiveHospitalId();
        List<PatientInsurance> visible = patientInsuranceRepository.findByPatient_Id(patientId)
            .stream()
            .filter(insurance -> insurance.isVisibleAt(hospitalId))
            .toList();
        return patientInsuranceMapper.toPatientInsuranceResponseDTOs(visible);
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO updatePatientInsurance(UUID insuranceId, PatientInsuranceRequestDTO dto, Locale locale) {
        PatientInsurance existing = getScopedInsuranceOrThrow(insuranceId);
        // The record's current owner first: a patient naming their own id in
        // the body must not rewrite, and so take over, another patient's
        // coverage. (The endpoint admits no patient today; the rule is the
        // service's, so a future caller cannot skip it.)
        enforceSelfAccessIfPatient(existing.getPatient(), () -> insuranceNotFound(insuranceId));

        Patient targetPatient = (dto.getPatientId() != null)
            ? getPatientOrThrow(dto.getPatientId())
            : existing.getPatient();

        if (targetPatient == null) {
            throw new FieldBusinessException("patientId", PATIENT_REQUIRED_KEY);
        }

        enforceSelfAccessIfPatient(targetPatient, () -> patientNotFound(targetPatient.getId()));

        // Billing-money plan PR 6: a plan named in the edit is linked by the same rule as the "Formule" picker.
        // Review of #1202: the edit never changes the row's hospital tag; a shared (untagged) row gets its own
        // copy only through the "Formule" picker.
        PlanLink planLink = planForEdit(existing, dto.getPayerPlanId(), insuranceId);

        // Apply changes (do not touch assignment here)
        patientInsuranceMapper.updateEntityFromDto(existing, dto, targetPatient);

        PatientInsurance saved = patientInsuranceRepository.save(existing);
        auditPlanLink(planLink, saved);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deletePatientInsurance(UUID insuranceId, Locale locale) {
        PatientInsurance existing = getScopedInsuranceOrThrow(insuranceId);
        enforceSelfAccessIfPatient(existing.getPatient(), () -> insuranceNotFound(insuranceId));
        patientInsuranceRepository.deleteById(insuranceId);
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO linkPatientInsurance(UUID insuranceId,
                                                            LinkPatientInsuranceRequestDTO req,
                                                            ActingContext ctx,
                                                            Locale locale) {
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId);
        Patient patient = getPatientOrThrow(req.getPatientId());

        // Decide acting mode
        boolean actAsPatient = isActingAsPatient(ctx);
        UUID actorUserId = resolveActorUserId(ctx);

        // A patient-only caller is held to their own rows whatever X-Act-As
        // says, before the staff checks below, whose answers (400/403) would
        // otherwise differ between a real id and a missing one.
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(req.getPatientId()));
        enforceSelfAccessIfPatient(insurance.getPatient(), () -> insuranceNotFound(insuranceId));

        if (actAsPatient) {
            // A patient links only their own row, and only coverage that is
            // unowned or already theirs: another patient's insurance record
            // could otherwise be re-pointed at the caller. Either refusal
            // answers exactly as the missing id does.
            enforcePatientSelfAccess(patient, () -> patientNotFound(req.getPatientId()));
            enforcePatientSelfAccess(insurance.getPatient(), () -> insuranceNotFound(insuranceId));
            rejectHospitalLinkForPatient(req);
            rejectPlanForPatient(req.getPayerPlanId(), insurance.getPayerPlanId());
        }
        PlanLink planLink = null;
        if (!actAsPatient) {
            UUID hospitalId = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalId, actorUserId, ctx, locale);
            patientChartAccess.require(patient.getId(), hospitalId);
            insurance = rowAtHospital(insurance, hospitalId, actorUserId, () -> insuranceNotFound(insuranceId));
            planLink = applyRequestedPlan(insurance, req.getPayerPlanId(), hospitalId, actorUserId,
                () -> insuranceNotFound(insuranceId));
        }

        // Always attach to patient
        insurance.setPatient(patient);

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        auditPlanLink(planLink, saved);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    /* ==================== helpers ==================== */

    /**
     * Same rule as the chart header: resolve unscoped, then authorize against the
     * registration table. {@code findById} is tenant-scoped on Patient.hospitalId
     * — the patient's FIRST hospital — so it 404'd insurance for every
     * multi-hospital patient viewed from their second hospital.
     *
     * <p>READS only. Every write in this class still resolves through
     * {@link #getPatientOrThrow}, deliberately: moving a write onto the
     * registration rule would let a caller at the patient's second hospital
     * create and relink coverage, which is an authorization decision rather
     * than the display fix this is. The split is recorded in tasklist.md so it
     * is a choice, not an oversight — if you are adding a read here, use this
     * method; if you are adding a write, do not change the rule on your own.
     */
    private Patient getPatientScoped(UUID patientId) {
        // A patient-only caller is bounded by ownership and has no hospital to
        // measure against (design Q1), so the adapter refuses them outright —
        // and the argument is evaluated before the call, so the owner branch
        // inside PatientChartAccess could never run: a patient was refused
        // their OWN coverage with 403 PATIENT_OWNED. Pass no hospital for that
        // one scope; the chart helper then admits the caller's own chart and
        // answers every other exactly as a missing one. Every other scope still
        // goes through the adapter, so a staff caller who named no hospital is
        // still told to select one.
        UUID hospitalId = actingScopeResolver.current() instanceof ActingScope.PatientOwned
            ? null
            : roleValidator.requireActiveHospitalId();
        return patientChartAccess.require(patientId, hospitalId);
    }

    private Patient getPatientOrThrow(UUID patientId) {
        return patientRepository.findById(patientId).orElseThrow(() -> patientNotFound(patientId));
    }

    /**
     * Review of #1131 (the D1590 class) and QA D1651: a read, an edit or a
     * delete by id reaches only
     * the rows of the hospital the request acts at, plus legacy rows tagged to
     * no hospital. Another hospital's row answers exactly as a missing one
     * (404), so B's desk can neither change nor remove A's coverage. A
     * patient-only caller is held to their own rows by the ownership check
     * that follows; a verified super-admin in global view has no hospital to
     * measure against.
     */
    private PatientInsurance getScopedInsuranceOrThrow(UUID insuranceId) {
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId);
        if (roleValidator.isPatientOnlyFromAuth()) {
            return insurance;
        }
        if (!insurance.isVisibleAt(roleValidator.requireActiveHospitalId())) {
            throw insuranceNotFound(insuranceId);
        }
        return insurance;
    }

    private PatientInsurance getInsuranceOrThrow(UUID insuranceId) {
        return patientInsuranceRepository.findById(insuranceId).orElseThrow(() -> insuranceNotFound(insuranceId));
    }

    /** The answer for a patient id that matches no row — and for one the caller may not name. */
    private static ResourceNotFoundException patientNotFound(UUID patientId) {
        return new ResourceNotFoundException("patient.notfound", patientId);
    }

    /** The answer for an insurance id that matches no row — and for one the caller may not read. */
    private static ResourceNotFoundException insuranceNotFound(UUID insuranceId) {
        return new ResourceNotFoundException("patientinsurance.notfound", insuranceId);
    }

    /**
     * A PATIENT may only act on their own row. Another patient's answers
     * exactly as a missing one ({@code notFound}), not 403: a 403 for a real
     * id beside a 404 for a made-up one told a patient which ids exist.
     * Ownership is the shared {@link PatientSubjectReadGuard#callerOwns}, so a
     * Keycloak patient (no {@code CustomUserDetails}) is recognised too.
     */
    private void enforceSelfAccessIfPatient(Patient patient, Supplier<ResourceNotFoundException> notFound) {
        if (roleValidator.isPatientOnlyFromAuth()) {
            enforcePatientSelfAccess(patient, notFound);
        }
    }

    @Transactional
    @Override
    public PatientInsuranceResponseDTO upsertAndLinkByInsuranceId(
        UUID insuranceId,
        LinkPatientInsuranceRequestDTO req,
        ActingContext ctx,
        Locale locale
    ) {
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId);

        if (req.getPatientId() == null) {
            throw new FieldBusinessException("patientId", PATIENT_REQUIRED_KEY);
        }
        Patient patient = getPatientOrThrow(req.getPatientId());
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(req.getPatientId()));
        // As in linkPatientInsurance: a patient-only caller is held to their own
        // coverage whatever X-Act-As says, before the staff checks answer.
        enforceSelfAccessIfPatient(insurance.getPatient(), () -> insuranceNotFound(insuranceId));

        boolean actAsPatient = isActingAsPatient(ctx);
        UUID actorUserId = resolveActorUserId(ctx);

        PlanLink planLink = null;
        if (actAsPatient) {
            enforcePatientSelfAccess(patient, () -> patientNotFound(req.getPatientId()));
            enforcePatientSelfAccess(insurance.getPatient(), () -> insuranceNotFound(insuranceId));
            rejectPlanForPatient(req.getPayerPlanId(), insurance.getPayerPlanId());
            insurance.setPatient(patient);
        } else {
            UUID hospitalId = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalId, actorUserId, ctx, locale);
            patientChartAccess.require(patient.getId(), hospitalId);
            insurance = rowAtHospital(insurance, hospitalId, actorUserId, () -> insuranceNotFound(insuranceId));
            insurance.setPatient(patient);
            planLink = applyRequestedPlan(insurance, req.getPayerPlanId(), hospitalId, actorUserId,
                () -> insuranceNotFound(insuranceId));
        }

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        auditPlanLink(planLink, saved);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO upsertAndLinkByNaturalKey(
        LinkPatientInsuranceRequestDTO req,
        ActingContext ctx,
        Locale locale
    ) {
        validateNaturalKeyParts(req);

        final UUID patientId = req.getPatientId();
        final String payerCode = req.getPayerCode().trim();
        final String policyNumber = req.getPolicyNumber().trim();

        Patient patient = getPatientOrThrow(patientId);
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(patientId));

        final boolean actAsPatient = isActingAsPatient(ctx);
        final UUID actorUserId = resolveActorUserId(ctx);

        UUID hospitalIdForStaff = null;
        if (actAsPatient) {
            enforcePatientSelfAccess(patient, () -> patientNotFound(patientId));
            rejectHospitalLinkForPatient(req);
        } else {
            hospitalIdForStaff = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalIdForStaff, actorUserId, ctx, locale);
            patientChartAccess.require(patientId, hospitalIdForStaff);
        }

        PatientInsurance found = findOrCreateInsurance(patientId, payerCode, policyNumber, patient,
            hospitalIdForStaff);
        if (actAsPatient) {
            rejectPlanForPatient(req.getPayerPlanId(), found.getPayerPlanId());
        }

        PlanLink planLink = null;
        final PatientInsurance insurance = actAsPatient ? found
            : rowAtHospital(found, hospitalIdForStaff, actorUserId, () -> insuranceNotFound(found.getId()));
        applyProviderName(insurance, req.getProviderName(), payerCode);
        if (!actAsPatient) {
            planLink = applyRequestedPlan(insurance, req.getPayerPlanId(), hospitalIdForStaff, actorUserId,
                () -> insuranceNotFound(insurance.getId()));
        }

        applyPrimaryFlag(req, actAsPatient, insurance, patient, hospitalIdForStaff);

        insurance.setLinkedByUserId(actorUserId);
        insurance.setLinkedAs(actAsPatient ? ROLE_PATIENT : "STAFF");

        PatientInsurance saved = patientInsuranceRepository.save(insurance);

        if (!actAsPatient && Boolean.TRUE.equals(req.getPrimary())) {
            patientInsuranceRepository.unsetOtherPrimariesForPatientInHospital(patient.getId(), saved.getId(), hospitalIdForStaff);
            saved.setPrimary(true);
            saved = patientInsuranceRepository.save(saved);
        }

        auditPlanLink(planLink, saved);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    /**
     * Billing-money plan PR 6 (plan section 3.8): the "Formule" picker. The caller holds HOSPITAL_ADMIN,
     * BILLING_SPECIALIST or RECEPTIONIST at the acting hospital (checked before any lookup); the coverage row is that
     * hospital's (another's answers 404, as a missing one); the plan is that hospital's (404 otherwise), of an active
     * payer, not ended. Null removes the link.
     */
    @Override
    @Transactional
    public PatientInsuranceResponseDTO setPayerPlan(UUID insuranceId, UUID payerPlanId, Locale locale) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(HOSPITAL_REQUIRED_KEY);
        }
        UUID actorUserId = resolveActorUserId(null);
        requirePlanLinker(hospitalId, actorUserId);
        PatientInsurance scoped = getScopedInsuranceOrThrow(insuranceId);
        if (Objects.equals(scoped.getPayerPlanId(), payerPlanId)) {
            return patientInsuranceMapper.toPatientInsuranceResponseDTO(scoped);
        }
        // Review of #1202: the patient is one this hospital may chart (registered here, or treated here), else
        // the row answers as a missing one; a shared row is never re-tagged: this hospital gets its own copy.
        patientChartAccess.require(scoped.getPatient() != null ? scoped.getPatient().getId() : null, hospitalId);
        PatientInsurance insurance = scoped.getAssignment() != null
            ? scoped
            : rowAtHospital(scoped, hospitalId, actorUserId, () -> insuranceNotFound(insuranceId));
        PlanLink planLink = applyPayerPlan(insurance, payerPlanId, hospitalId, actorUserId,
            () -> insuranceNotFound(insuranceId));
        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        auditPlanLink(planLink, saved);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    /* ==================== payer plan link (billing-money plan PR 6) ==================== */

    /**
     * Review of #1202: a plan named in the edit, linked by the picker's rules (plan role, a patient this hospital may
     * chart, a plan of this hospital). The edit never changes the row's hospital tag: a shared row is refused and
     * sent to the picker, which gives this hospital its own copy. Naming the plan the row already has changes nothing.
     */
    private PlanLink planForEdit(PatientInsurance existing, UUID payerPlanId, UUID insuranceId) {
        if (payerPlanId == null || Objects.equals(payerPlanId, existing.getPayerPlanId())) {
            return null;
        }
        rejectPlanForPatientOnly(payerPlanId);
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(HOSPITAL_REQUIRED_KEY);
        }
        UUID actorUserId = resolveActorUserId(null);
        requirePlanLinker(hospitalId, actorUserId);
        patientChartAccess.require(existing.getPatient() != null ? existing.getPatient().getId() : null, hospitalId);
        if (existing.getAssignment() == null) {
            throw new FieldBusinessException("payerPlanId", "patientinsurance.payerPlan.sharedRow");
        }
        return applyPayerPlan(existing, payerPlanId, hospitalId, actorUserId, () -> insuranceNotFound(insuranceId));
    }

    /** A change of a row's plan, audited after the row is saved (a new row has no id before). */
    private record PlanLink(UUID hospitalId, UUID actorUserId) {
    }

    /** A plan named in a link or an edit request: linked by the picker's rule; null leaves the link as it is. */
    private PlanLink applyRequestedPlan(PatientInsurance insurance, UUID payerPlanId, UUID hospitalId,
                                        UUID actorUserId, Supplier<ResourceNotFoundException> notFound) {
        if (payerPlanId == null || Objects.equals(insurance.getPayerPlanId(), payerPlanId)) {
            return null;
        }
        requirePlanLinker(hospitalId, actorUserId);
        return applyPayerPlan(insurance, payerPlanId, hospitalId, actorUserId, notFound);
    }

    /**
     * The one place a coverage row's plan changes (PayerCatalogueGuardTest). A plan of the row's hospital only, on a
     * row tagged to that hospital: a shared (untagged) row reaches here only as this hospital's own copy
     * ({@link #rowAtHospital}), and any other row answers exactly as a missing one. Never changes the tag.
     */
    private PlanLink applyPayerPlan(PatientInsurance insurance, UUID payerPlanId, UUID hospitalId, UUID actorUserId,
                                    Supplier<ResourceNotFoundException> notFound) {
        if (Objects.equals(insurance.getPayerPlanId(), payerPlanId)) {
            return null;
        }
        if (payerPlanId != null) {
            payerCatalogueService.requireLinkablePlan(payerPlanId, hospitalId);
            if (insurance.getAssignment() == null || !insurance.isVisibleAt(hospitalId)) {
                throw notFound.get();
            }
        }
        insurance.setPayerPlanId(payerPlanId);
        return new PlanLink(hospitalId, actorUserId);
    }

    /** Only the hospital admin, the billing specialist and reception link a coverage to a plan (plan section 3.8). */
    private void requirePlanLinker(UUID hospitalId, UUID actorUserId) {
        if (actorUserId == null || !roleValidator.canLinkPayerPlan(actorUserId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("patientinsurance.payerPlan.forbidden"));
        }
    }

    /**
     * A patient acting as a patient never picks the plan their coverage is billed under; naming the plan it already
     * has changes nothing and is let through.
     */
    private static void rejectPlanForPatient(UUID payerPlanId, UUID current) {
        if (payerPlanId != null && !payerPlanId.equals(current)) {
            throw new FieldBusinessException("payerPlanId", "patientinsurance.payerPlan.staffOnly");
        }
    }

    private void rejectPlanForPatientOnly(UUID payerPlanId) {
        if (roleValidator.isPatientOnlyFromAuth()) {
            rejectPlanForPatient(payerPlanId, null);
        }
    }

    /**
     * Review of #1202 (the re-tag class): the row a staff write at {@code hospitalId} acts on. A new row is tagged to
     * the hospital; a row already this hospital's is re-stamped with the caller's assignment there; another
     * hospital's answers as a missing one. A SHARED row (tagged to no hospital, so visible to every hospital) is never
     * re-tagged, which would take it away from every other hospital: this hospital gets its own copy (decision
     * 2026-11-12, "B gets its own copy through its own desk"), found again on a later write, and the shared row stays
     * as it was.
     */
    private PatientInsurance rowAtHospital(PatientInsurance insurance, UUID hospitalId, UUID actorUserId,
                                           Supplier<ResourceNotFoundException> notFound) {
        UserRoleHospitalAssignment assignment = resolveStaffAssignment(actorUserId, hospitalId);
        if (insurance.getId() == null || insurance.getAssignment() != null) {
            tagForStaffHospital(insurance, assignment, notFound);
            return insurance;
        }
        PatientInsurance copy = existingCopyAt(insurance, hospitalId).orElseGet(() -> copyOf(insurance));
        tagForStaffHospital(copy, assignment, notFound);
        copy.setLinkedByUserId(actorUserId);
        copy.setLinkedAs("STAFF");
        return copy;
    }

    /** This hospital's copy of a shared row, made by an earlier write (same patient, payer or insurer, policy). */
    private java.util.Optional<PatientInsurance> existingCopyAt(PatientInsurance shared, UUID hospitalId) {
        UUID patientId = shared.getPatient() != null ? shared.getPatient().getId() : null;
        if (patientId == null || shared.getPolicyNumber() == null) {
            return java.util.Optional.empty();
        }
        if (shared.getPayerCode() != null) {
            return patientInsuranceRepository
                .findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, hospitalId, shared.getPayerCode(), shared.getPolicyNumber());
        }
        return patientInsuranceRepository
            .findFirstByPatient_IdAndAssignment_Hospital_IdAndProviderNameIgnoreCaseAndPolicyNumberIgnoreCase(
                patientId, hospitalId, shared.getProviderName(), shared.getPolicyNumber());
    }

    /** A new, untagged row with the shared row's coverage details (no plan: the caller sets it). */
    private static PatientInsurance copyOf(PatientInsurance shared) {
        return PatientInsurance.builder()
            .patient(shared.getPatient())
            .payerCode(shared.getPayerCode())
            .providerName(shared.getProviderName())
            .policyNumber(shared.getPolicyNumber())
            .groupNumber(shared.getGroupNumber())
            .subscriberName(shared.getSubscriberName())
            .subscriberRelationship(shared.getSubscriberRelationship())
            .effectiveDate(shared.getEffectiveDate())
            .expirationDate(shared.getExpirationDate())
            .primary(false)
            .build();
    }

    private void auditPlanLink(PlanLink planLink, PatientInsurance saved) {
        if (planLink != null && saved != null) {
            payerCatalogueService.auditCoverageLink(saved.getId(), saved.getPayerPlanId(), planLink.hospitalId(),
                planLink.actorUserId());
        }
    }

    /* ==================== shared helpers ==================== */

    private boolean isActingAsPatient(ActingContext ctx) {
        return ctx != null && ctx.mode() != null && ROLE_PATIENT.equalsIgnoreCase(ctx.mode().name());
    }

    /**
     * The acting user. {@code ActingContext.userId} is filled only for a
     * password-login principal, and {@code RoleValidator.getCurrentUserId()} is
     * null on a Keycloak token, so the caller is resolved the way every
     * patient-subject read resolves it (the {@code appUserId} claim).
     */
    private UUID resolveActorUserId(ActingContext ctx) {
        return (ctx != null && ctx.userId() != null) ? ctx.userId() : subjectReadGuard.callerUserId().orElse(null);
    }

    /**
     * Acting as PATIENT: the row must be the caller's own. {@code null} (an
     * insurance record not yet linked to anyone) passes. A foreign row
     * answers exactly as a missing one.
     */
    private void enforcePatientSelfAccess(Patient patient, Supplier<ResourceNotFoundException> notFound) {
        if (patient != null && !subjectReadGuard.callerOwns(patient)) {
            throw notFound.get();
        }
    }

    private void rejectHospitalLinkForPatient(LinkPatientInsuranceRequestDTO req) {
        if (req.getHospitalId() != null) {
            throw new com.example.hms.exception.FieldBusinessException("hospitalId", "patient.cannot.link.hospital");
        }
    }

    private void enforceStaffAuthorization(UUID hospitalId, UUID actorUserId, ActingContext ctx, Locale locale) {
        if (hospitalId == null) {
            throw new BusinessException(HOSPITAL_REQUIRED_KEY);
        }
        final boolean hasChosenRole = ctx != null && ctx.roleCode() != null && !ctx.roleCode().isBlank();
        final boolean jwtGlobalOverride = roleValidator.isSuperAdminFromAuth()
            || roleValidator.isHospitalAdminFromAuthGlobalOnly();

        if (hasChosenRole) {
            roleValidator.validateRoleOrThrow(actorUserId, hospitalId, ctx.roleCode().trim());
        } else {
            final boolean hasScopedPermission = roleValidator.canLinkInsurance(actorUserId, hospitalId);
            if (!hasScopedPermission && !jwtGlobalOverride) {
                throw new AccessDeniedException(
                    messageSource.getMessage(INSURANCE_LINK_FORBIDDEN_KEY, null,
                        INSURANCE_LINK_FORBIDDEN_MSG, locale));
            }
        }
    }

    /**
     * Review of #1131 (the D1590 class): a staff link tags the row with the
     * caller's hospital. A row another hospital already holds is never
     * re-tagged (it would vanish from that desk and could become this
     * hospital's primary); it answers exactly as a missing row does.
     */
    private static void tagForStaffHospital(PatientInsurance insurance, UserRoleHospitalAssignment assignment,
                                            Supplier<ResourceNotFoundException> notFound) {
        UserRoleHospitalAssignment held = insurance.getAssignment();
        UUID heldBy = held != null && held.getHospital() != null ? held.getHospital().getId() : null;
        UUID caller = assignment.getHospital() != null ? assignment.getHospital().getId() : null;
        if (heldBy != null && !heldBy.equals(caller)) {
            throw notFound.get();
        }
        // Review of #1202: an existing shared row is never re-tagged (rowAtHospital copies it instead).
        if (held == null && insurance.getId() != null) {
            throw new IllegalStateException("patientinsurance.sharedRow.neverRetagged");
        }
        insurance.setAssignment(assignment);
    }

    private UserRoleHospitalAssignment resolveStaffAssignment(UUID actorUserId, UUID hospitalId) {
        return assignmentRepository
            .findFirstByUser_IdAndHospital_IdAndActiveTrue(actorUserId, hospitalId)
            .orElseThrow(() -> new BusinessException(ASSIGNMENT_REQUIRED_KEY));
    }

    private void validateNaturalKeyParts(LinkPatientInsuranceRequestDTO req) {
        // QA D1355 class (round 30): the patient, the payer and the policy named in one refusal.
        com.example.hms.exception.RequiredFields.check()
            .requireValue(req == null ? null : req.getPatientId(), "patientId", PATIENT_REQUIRED_KEY)
            .requireValue(req == null ? null : req.getPayerCode(), "payerCode", "patientinsurance.payer.required")
            .requireValue(req == null ? null : req.getPolicyNumber(), "policyNumber", "patientinsurance.policy.required")
            .orThrow();
    }

    /**
     * Review of #1131 (the D1590 class): the policy as the caller's hospital
     * holds it, else one tagged to no hospital yet, else a new row. A staff
     * link at hospital B never finds, and so never re-tags or promotes,
     * hospital A's row; B gets its own. A patient (no hospital) reaches only
     * untagged rows.
     */
    private PatientInsurance findOrCreateInsurance(UUID patientId, String payerCode, String policyNumber, Patient patient,
                                                   UUID hospitalId) {
        java.util.Optional<PatientInsurance> here = hospitalId == null
            ? java.util.Optional.empty()
            : patientInsuranceRepository
                .findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, hospitalId, payerCode, policyNumber);
        PatientInsurance insurance = here
            .or(() -> patientInsuranceRepository
                .findFirstByPatient_IdAndAssignmentIsNullAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, payerCode, policyNumber))
            .orElse(null);

        if (insurance == null) {
            insurance = new PatientInsurance();
            insurance.setPatient(patient);
            insurance.setPayerCode(payerCode);
            insurance.setPolicyNumber(policyNumber);
        } else {
            insurance.setPatient(patient);
        }
        return insurance;
    }

    /**
     * QA D925: {@code provider_name} is required, and a policy created through
     * the natural-key link had none, so every new link answered 500 (or 400
     * "providerName: must not be blank" when it was also made primary). A
     * typed name wins; otherwise a new policy keeps the payer code as its name
     * and an existing one keeps the name it has.
     */
    private static void applyProviderName(PatientInsurance insurance, String typed, String payerCode) {
        String name = typed == null ? null : typed.trim();
        if (name != null && !name.isEmpty()) {
            insurance.setProviderName(name);
        } else if (insurance.getProviderName() == null || insurance.getProviderName().isBlank()) {
            insurance.setProviderName(payerCode);
        }
    }

    private void applyPrimaryFlag(LinkPatientInsuranceRequestDTO req, boolean actAsPatient,
                                  PatientInsurance insurance, Patient patient, UUID hospitalId) {
        if (actAsPatient || req.getPrimary() == null) {
            return;
        }
        if (Boolean.TRUE.equals(req.getPrimary())) {
            patientInsuranceRepository.unsetOtherPrimariesForPatientInHospital(patient.getId(), insurance.getId(), hospitalId);
            insurance.setPrimary(true);
        } else {
            insurance.setPrimary(false);
        }
    }

    private UUID resolveHospitalId(LinkPatientInsuranceRequestDTO req, ActingContext ctx) {
        if (req.getHospitalId() != null) {
            return req.getHospitalId();
        }
        return ctx != null ? ctx.hospitalId() : null;
    }

}
