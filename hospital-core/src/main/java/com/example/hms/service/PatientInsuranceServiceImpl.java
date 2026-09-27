package com.example.hms.service;

import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.security.ActingContext;
import com.example.hms.exception.BusinessException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class PatientInsuranceServiceImpl implements PatientInsuranceService {
    private static final String PATIENT_REQUIRED_KEY = "patientinsurance.patient.required";
    private static final String PATIENT_REQUIRED_MSG = "Patient is required for insurance";
    private static final String ROLE_PATIENT = "PATIENT";
    /** PatientChartAccess's key for a patient id that matches no row (the list read's miss). */
    private static final String CHART_PATIENT_NOT_FOUND_KEY = "patient.notFound";
    private static final String HOSPITAL_REQUIRED_KEY = "hospital.required";
    private static final String HOSPITAL_REQUIRED_MSG = "Hospital context is required";
    private static final String INSURANCE_LINK_FORBIDDEN_KEY = "insurance.link.forbidden";
    private static final String INSURANCE_LINK_FORBIDDEN_MSG = "You don't have permission to link insurance in this hospital";
    private static final String ASSIGNMENT_REQUIRED_KEY = "assignment.required";
    private static final String ASSIGNMENT_REQUIRED_MSG = "Valid assignment required";


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

    @Override
    @Transactional
    public PatientInsuranceResponseDTO addInsuranceToPatient(PatientInsuranceRequestDTO dto, Locale locale) {
        if (dto.getPatientId() == null) {
            throw new BusinessException(
                messageSource.getMessage(PATIENT_REQUIRED_KEY,
                    null, PATIENT_REQUIRED_MSG, locale));
        }

        Patient patient = getPatientOrThrow(dto.getPatientId(), locale);
        // PATIENT may only act on self; another patient answers as a missing one
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(dto.getPatientId(), locale));

        PatientInsurance insurance = patientInsuranceMapper.toPatientInsurance(dto, patient);

        // Do NOT stamp assignment during create
        insurance.setAssignment(null);

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientInsuranceResponseDTO getPatientInsuranceById(UUID insuranceId, Locale locale) {
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId, locale);
        // Another patient's insurance answers exactly as a missing id does.
        enforceSelfAccessIfPatient(insurance.getPatient(), () -> insuranceNotFound(insuranceId, locale));
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

        return patientInsuranceRepository.findByPatient_Id(patientId)
            .stream()
            .map(patientInsuranceMapper::toPatientInsuranceResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO updatePatientInsurance(UUID insuranceId, PatientInsuranceRequestDTO dto, Locale locale) {
        PatientInsurance existing = getInsuranceOrThrow(insuranceId, locale);
        // The record's current owner first: a patient naming their own id in
        // the body must not rewrite, and so take over, another patient's
        // coverage. (The endpoint admits no patient today; the rule is the
        // service's, so a future caller cannot skip it.)
        enforceSelfAccessIfPatient(existing.getPatient(), () -> insuranceNotFound(insuranceId, locale));

        Patient targetPatient = (dto.getPatientId() != null)
            ? getPatientOrThrow(dto.getPatientId(), locale)
            : existing.getPatient();

        if (targetPatient == null) {
            throw new BusinessException(
                messageSource.getMessage(PATIENT_REQUIRED_KEY,
                    null, PATIENT_REQUIRED_MSG, locale));
        }

        enforceSelfAccessIfPatient(targetPatient, () -> patientNotFound(targetPatient.getId(), locale));

        // Apply changes (do not touch assignment here)
        patientInsuranceMapper.updateEntityFromDto(existing, dto, targetPatient);

        PatientInsurance saved = patientInsuranceRepository.save(existing);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deletePatientInsurance(UUID insuranceId, Locale locale) {
        PatientInsurance existing = getInsuranceOrThrow(insuranceId, locale);
        enforceSelfAccessIfPatient(existing.getPatient(), () -> insuranceNotFound(insuranceId, locale));
        patientInsuranceRepository.deleteById(insuranceId);
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO linkPatientInsurance(UUID insuranceId,
                                                            LinkPatientInsuranceRequestDTO req,
                                                            ActingContext ctx,
                                                            Locale locale) {
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId, locale);
        Patient patient = getPatientOrThrow(req.getPatientId(), locale);

        // Decide acting mode
        boolean actAsPatient = isActingAsPatient(ctx);
        UUID actorUserId = resolveActorUserId(ctx);

        // A patient-only caller is held to their own rows whatever X-Act-As
        // says, before the staff checks below, whose answers (400/403) would
        // otherwise differ between a real id and a missing one.
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(req.getPatientId(), locale));
        enforceSelfAccessIfPatient(insurance.getPatient(), () -> insuranceNotFound(insuranceId, locale));

        if (actAsPatient) {
            // A patient links only their own row, and only coverage that is
            // unowned or already theirs: another patient's insurance record
            // could otherwise be re-pointed at the caller. Either refusal
            // answers exactly as the missing id does.
            enforcePatientSelfAccess(patient, () -> patientNotFound(req.getPatientId(), locale));
            enforcePatientSelfAccess(insurance.getPatient(), () -> insuranceNotFound(insuranceId, locale));
            rejectHospitalLinkForPatient(req, locale);
        } else {
            UUID hospitalId = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalId, actorUserId, ctx, locale);
            insurance.setAssignment(resolveStaffAssignment(actorUserId, hospitalId, locale));
        }

        // Always attach to patient
        insurance.setPatient(patient);

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
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
        return patientChartAccess.require(patientId, roleValidator.requireActiveHospitalId());
    }

    private Patient getPatientOrThrow(UUID patientId, Locale locale) {
        return patientRepository.findById(patientId).orElseThrow(() -> patientNotFound(patientId, locale));
    }

    private PatientInsurance getInsuranceOrThrow(UUID insuranceId, Locale locale) {
        return patientInsuranceRepository.findById(insuranceId).orElseThrow(() -> insuranceNotFound(insuranceId, locale));
    }

    /** The answer for a patient id that matches no row — and for one the caller may not name. */
    private ResourceNotFoundException patientNotFound(UUID patientId, Locale locale) {
        return new ResourceNotFoundException(
            messageSource.getMessage("patient.notfound", new Object[]{patientId}, "Patient not found", locale));
    }

    /** The answer for an insurance id that matches no row — and for one the caller may not read. */
    private ResourceNotFoundException insuranceNotFound(UUID insuranceId, Locale locale) {
        return new ResourceNotFoundException(
            messageSource.getMessage("patientinsurance.notfound", new Object[]{insuranceId},
                "Patient insurance not found", locale));
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
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId, locale);

        if (req.getPatientId() == null) {
            throw new BusinessException(messageSource.getMessage(
                PATIENT_REQUIRED_KEY, null, PATIENT_REQUIRED_MSG, locale));
        }
        Patient patient = getPatientOrThrow(req.getPatientId(), locale);
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(req.getPatientId(), locale));
        // As in linkPatientInsurance: a patient-only caller is held to their own
        // coverage whatever X-Act-As says, before the staff checks answer.
        enforceSelfAccessIfPatient(insurance.getPatient(), () -> insuranceNotFound(insuranceId, locale));

        boolean actAsPatient = isActingAsPatient(ctx);
        UUID actorUserId = resolveActorUserId(ctx);

        if (actAsPatient) {
            enforcePatientSelfAccess(patient, () -> patientNotFound(req.getPatientId(), locale));
            enforcePatientSelfAccess(insurance.getPatient(), () -> insuranceNotFound(insuranceId, locale));
            insurance.setPatient(patient);
        } else {
            insurance.setPatient(patient);
            UUID hospitalId = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalId, actorUserId, ctx, locale);
            insurance.setAssignment(resolveStaffAssignment(actorUserId, hospitalId, locale));
        }

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO upsertAndLinkByNaturalKey(
        LinkPatientInsuranceRequestDTO req,
        ActingContext ctx,
        Locale locale
    ) {
        validateNaturalKeyParts(req, locale);

        final UUID patientId = req.getPatientId();
        final String payerCode = req.getPayerCode().trim();
        final String policyNumber = req.getPolicyNumber().trim();

        Patient patient = getPatientOrThrow(patientId, locale);
        enforceSelfAccessIfPatient(patient, () -> patientNotFound(patientId, locale));

        final boolean actAsPatient = isActingAsPatient(ctx);
        final UUID actorUserId = resolveActorUserId(ctx);

        UUID hospitalIdForStaff = null;
        if (actAsPatient) {
            enforcePatientSelfAccess(patient, () -> patientNotFound(patientId, locale));
            rejectHospitalLinkForPatient(req, locale);
        } else {
            hospitalIdForStaff = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalIdForStaff, actorUserId, ctx, locale);
        }

        PatientInsurance insurance = findOrCreateInsurance(patientId, payerCode, policyNumber, patient);

        if (!actAsPatient) {
            insurance.setAssignment(resolveStaffAssignment(actorUserId, hospitalIdForStaff, locale));
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

        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
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

    private void rejectHospitalLinkForPatient(LinkPatientInsuranceRequestDTO req, Locale locale) {
        if (req.getHospitalId() != null) {
            throw new BusinessException(
                messageSource.getMessage("patient.cannot.link.hospital", null,
                    "Patients cannot link insurance to a hospital", locale));
        }
    }

    private void enforceStaffAuthorization(UUID hospitalId, UUID actorUserId, ActingContext ctx, Locale locale) {
        if (hospitalId == null) {
            throw new BusinessException(
                messageSource.getMessage(HOSPITAL_REQUIRED_KEY, null, HOSPITAL_REQUIRED_MSG, locale));
        }
        final boolean hasChosenRole = ctx != null && ctx.roleCode() != null && !ctx.roleCode().isBlank();
        final boolean jwtGlobalOverride = roleValidator.isSuperAdminFromAuth()
            || roleValidator.isHospitalAdminFromAuthGlobalOnly();

        if (hasChosenRole) {
            roleValidator.validateRoleOrThrow(actorUserId, hospitalId, ctx.roleCode().trim(), locale, messageSource);
        } else {
            final boolean hasScopedPermission = roleValidator.canLinkInsurance(actorUserId, hospitalId);
            if (!hasScopedPermission && !jwtGlobalOverride) {
                throw new AccessDeniedException(
                    messageSource.getMessage(INSURANCE_LINK_FORBIDDEN_KEY, null,
                        INSURANCE_LINK_FORBIDDEN_MSG, locale));
            }
        }
    }

    private UserRoleHospitalAssignment resolveStaffAssignment(UUID actorUserId, UUID hospitalId, Locale locale) {
        return assignmentRepository
            .findFirstByUser_IdAndHospital_IdAndActiveTrue(actorUserId, hospitalId)
            .orElseThrow(() -> new BusinessException(
                messageSource.getMessage(ASSIGNMENT_REQUIRED_KEY, null, ASSIGNMENT_REQUIRED_MSG, locale)));
    }

    private void validateNaturalKeyParts(LinkPatientInsuranceRequestDTO req, Locale locale) {
        if (req == null || req.getPatientId() == null) {
            throw new BusinessException(messageSource.getMessage(
                PATIENT_REQUIRED_KEY, null, PATIENT_REQUIRED_MSG, locale));
        }
        if (req.getPayerCode() == null || req.getPayerCode().isBlank()) {
            throw new BusinessException(messageSource.getMessage(
                "patientinsurance.payer.required", null, "Payer code is required", locale));
        }
        if (req.getPolicyNumber() == null || req.getPolicyNumber().isBlank()) {
            throw new BusinessException(messageSource.getMessage(
                "patientinsurance.policy.required", null, "Policy number is required", locale));
        }
    }

    private PatientInsurance findOrCreateInsurance(UUID patientId, String payerCode, String policyNumber, Patient patient) {
        PatientInsurance insurance = patientInsuranceRepository
            .findByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(patientId, payerCode, policyNumber)
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
