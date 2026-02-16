package com.example.hms.service;

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

@Service
@RequiredArgsConstructor
public class PatientInsuranceServiceImpl implements PatientInsuranceService {
    private static final String PATIENT_REQUIRED_KEY = "patientinsurance.patient.required";
    private static final String PATIENT_REQUIRED_MSG = "Patient is required for insurance";
    private static final String ROLE_PATIENT = "PATIENT";
    private static final String ACCESS_DENIED_SELF_KEY = "access.denied.self";
    private static final String ACCESS_DENIED_SELF_MSG = "You can only access your own insurance details";
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

    @Override
    @Transactional
    public PatientInsuranceResponseDTO addInsuranceToPatient(PatientInsuranceRequestDTO dto, Locale locale) {
        if (dto.getPatientId() == null) {
            throw new BusinessException(
                messageSource.getMessage(PATIENT_REQUIRED_KEY,
                    null, PATIENT_REQUIRED_MSG, locale));
        }

        Patient patient = getPatientOrThrow(dto.getPatientId(), locale);
        enforceSelfAccessIfPatient(patient, locale); // PATIENT may only act on self

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
        if (insurance.getPatient() != null) {
            enforceSelfAccessIfPatient(insurance.getPatient(), locale);
        }
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientInsuranceResponseDTO> getInsurancesByPatientId(UUID patientId, Locale locale) {
        Patient patient = getPatientOrThrow(patientId, locale);
        enforceSelfAccessIfPatient(patient, locale);

        return patientInsuranceRepository.findByPatient_Id(patientId)
            .stream()
            .map(patientInsuranceMapper::toPatientInsuranceResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public PatientInsuranceResponseDTO updatePatientInsurance(UUID insuranceId, PatientInsuranceRequestDTO dto, Locale locale) {
        PatientInsurance existing = getInsuranceOrThrow(insuranceId, locale);

        Patient targetPatient = (dto.getPatientId() != null)
            ? getPatientOrThrow(dto.getPatientId(), locale)
            : existing.getPatient();

        if (targetPatient == null) {
            throw new BusinessException(
                messageSource.getMessage(PATIENT_REQUIRED_KEY,
                    null, PATIENT_REQUIRED_MSG, locale));
        }

        enforceSelfAccessIfPatient(targetPatient, locale);

        // Apply changes (do not touch assignment here)
        patientInsuranceMapper.updateEntityFromDto(existing, dto, targetPatient);

        PatientInsurance saved = patientInsuranceRepository.save(existing);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deletePatientInsurance(UUID insuranceId, Locale locale) {
        PatientInsurance existing = getInsuranceOrThrow(insuranceId, locale);
        if (existing.getPatient() != null) {
            enforceSelfAccessIfPatient(existing.getPatient(), locale);
        }
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

        // Always attach to patient
        insurance.setPatient(patient);

        // Decide acting mode
        boolean actAsPatient = isActingAsPatient(ctx);
        UUID actorUserId = resolveActorUserId(ctx);

        if (actAsPatient) {
            enforcePatientSelfAccess(patient, actorUserId, locale);
            rejectHospitalLinkForPatient(req, locale);
        } else {
            UUID hospitalId = resolveHospitalId(req, ctx);
            enforceStaffAuthorization(hospitalId, actorUserId, ctx, locale);
            insurance.setAssignment(resolveStaffAssignment(actorUserId, hospitalId, locale));
        }

        PatientInsurance saved = patientInsuranceRepository.save(insurance);
        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }

    /* ==================== helpers ==================== */

    private Patient getPatientOrThrow(UUID patientId, Locale locale) {
        return patientRepository.findById(patientId).orElseThrow(() ->
            new ResourceNotFoundException(
                messageSource.getMessage("patient.notfound",
                    new Object[]{patientId}, "Patient not found", locale)));
    }

    private PatientInsurance getInsuranceOrThrow(UUID insuranceId, Locale locale) {
        return patientInsuranceRepository.findById(insuranceId).orElseThrow(() ->
            new ResourceNotFoundException(
                messageSource.getMessage("patientinsurance.notfound",
                    new Object[]{insuranceId}, "Patient insurance not found", locale)));
    }

    private void enforceSelfAccessIfPatient(Patient patient, Locale locale) {
        if (roleValidator.isPatientOnlyFromAuth()) {
            UUID currentUserId = roleValidator.getCurrentUserId();
            UUID patientUserId = (patient.getUser() != null ? patient.getUser().getId() : null);
            if (patientUserId == null || !patientUserId.equals(currentUserId)) {
                throw new AccessDeniedException(
                    messageSource.getMessage(ACCESS_DENIED_SELF_KEY, null,
                        ACCESS_DENIED_SELF_MSG, locale));
            }
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
        enforceSelfAccessIfPatient(patient, locale);
        insurance.setPatient(patient);

        boolean actAsPatient = isActingAsPatient(ctx);
        UUID actorUserId = resolveActorUserId(ctx);

        if (actAsPatient) {
            enforcePatientSelfAccess(patient, actorUserId, locale);
        } else {
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
        enforceSelfAccessIfPatient(patient, locale);

        final boolean actAsPatient = isActingAsPatient(ctx);
        final UUID actorUserId = resolveActorUserId(ctx);

        UUID hospitalIdForStaff = null;
        if (actAsPatient) {
            enforcePatientSelfAccess(patient, actorUserId, locale);
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

    private UUID resolveActorUserId(ActingContext ctx) {
        return (ctx != null && ctx.userId() != null) ? ctx.userId() : roleValidator.getCurrentUserId();
    }

    private void enforcePatientSelfAccess(Patient patient, UUID actorUserId, Locale locale) {
        UUID patientUserId = patient.getUser() != null ? patient.getUser().getId() : null;
        if (patientUserId == null || !patientUserId.equals(actorUserId)) {
            throw new AccessDeniedException(
                messageSource.getMessage(ACCESS_DENIED_SELF_KEY, null, ACCESS_DENIED_SELF_MSG, locale));
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
