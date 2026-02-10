package com.example.hms.service;

import com.example.hms.security.ActingContext;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PatientInsuranceServiceImpl implements PatientInsuranceService {

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
                messageSource.getMessage("patientinsurance.patient.required",
                    null, "Patient is required for insurance", locale));
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
            .collect(Collectors.toList());
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
                messageSource.getMessage("patientinsurance.patient.required",
                    null, "Patient is required for insurance", locale));
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
        boolean actAsPatient = (ctx != null && ctx.mode() != null && ctx.mode().name().equalsIgnoreCase("PATIENT"));
        UUID actorUserId = (ctx != null && ctx.userId() != null) ? ctx.userId() : roleValidator.getCurrentUserId();

        if (actAsPatient) {
            // PATIENT path: must be their own record; no hospital linking allowed
            UUID patientUserId = (patient.getUser() != null ? patient.getUser().getId() : null);
            if (patientUserId == null || !patientUserId.equals(actorUserId)) {
                throw new AccessDeniedException(
                    messageSource.getMessage("access.denied.self", null,
                        "You can only access your own insurance details", locale));
            }
            if (req.getHospitalId() != null) {
                throw new BusinessException(
                    messageSource.getMessage("patient.cannot.link.hospital", null,
                        "Patients cannot link insurance to a hospital", locale));
            }
            // Do NOT modify assignment in patient mode
        } else {
            // STAFF/Admin path
            UUID hospitalId = (req.getHospitalId() != null) ? req.getHospitalId()
                : (ctx != null ? ctx.hospitalId() : null);
            if (hospitalId == null) {
                throw new BusinessException(
                    messageSource.getMessage("hospital.required", null,
                        "Hospital context is required", locale));
            }

            // Permission gate (same logic as before)
            final boolean hasChosenRole = ctx != null && ctx.roleCode() != null && !ctx.roleCode().isBlank();
            final boolean jwtGlobalOverride = roleValidator.isSuperAdminFromAuth()
                || roleValidator.isHospitalAdminFromAuthGlobalOnly();

            if (hasChosenRole) {
                roleValidator.validateRoleOrThrow(actorUserId, hospitalId, ctx.roleCode().trim(), locale, messageSource);
            } else {
                final boolean hasScopedPermission = roleValidator.canLinkInsurance(actorUserId, hospitalId);
                if (!hasScopedPermission && !jwtGlobalOverride) {
                    throw new AccessDeniedException(
                        messageSource.getMessage("insurance.link.forbidden", null,
                            "You don't have permission to link insurance in this hospital", locale));
                }
            }

            // Assignment stamping (STRICT)
            var assignment = assignmentRepository
                .findFirstByUser_IdAndHospital_IdAndActiveTrue(actorUserId, hospitalId)
                .orElseThrow(() -> new BusinessException(
                    messageSource.getMessage("assignment.required", null,
                        "Valid assignment required", locale)));

            insurance.setAssignment(assignment);
        }

        // No primary handling anymore — ignore req.getPrimary()

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
                    messageSource.getMessage("access.denied.self", null,
                        "You can only access your own insurance details", locale));
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
        // 1) Load existing insurance; if not found, fail (no creation here)
        PatientInsurance insurance = getInsuranceOrThrow(insuranceId, locale);

        // 2) Attach to patient (required)
        if (req.getPatientId() == null) {
            throw new BusinessException(messageSource.getMessage(
                "patientinsurance.patient.required", null, "Patient is required for insurance", locale));
        }
        Patient patient = getPatientOrThrow(req.getPatientId(), locale);
        enforceSelfAccessIfPatient(patient, locale);
        insurance.setPatient(patient);

        // 3) Acting mode / hospital assignment (same logic you already have)
        boolean actAsPatient = (ctx != null && ctx.mode() != null && ctx.mode().name().equalsIgnoreCase("PATIENT"));
        UUID actorUserId = (ctx != null && ctx.userId() != null) ? ctx.userId() : roleValidator.getCurrentUserId();

        if (actAsPatient) {
            UUID patientUserId = (patient.getUser() != null ? patient.getUser().getId() : null);
            if (patientUserId == null || !patientUserId.equals(actorUserId)) {
                throw new AccessDeniedException(messageSource.getMessage(
                    "access.denied.self", null, "You can only access your own insurance details", locale));
            }
            // No hospital assignment stamping in PATIENT mode
        } else {
            UUID hospitalId = (req.getHospitalId() != null) ? req.getHospitalId() : (ctx != null ? ctx.hospitalId() : null);
            if (hospitalId == null) {
                throw new BusinessException(messageSource.getMessage("hospital.required", null, "Hospital context is required", locale));
            }

            final boolean hasChosenRole = ctx != null && ctx.roleCode() != null && !ctx.roleCode().isBlank();
            final boolean jwtGlobalOverride = roleValidator.isSuperAdminFromAuth() || roleValidator.isHospitalAdminFromAuthGlobalOnly();

            if (hasChosenRole) {
                roleValidator.validateRoleOrThrow(actorUserId, hospitalId, ctx.roleCode().trim(), locale, messageSource);
            } else {
                final boolean hasScopedPermission = roleValidator.canLinkInsurance(actorUserId, hospitalId);
                if (!hasScopedPermission && !jwtGlobalOverride) {
                    throw new AccessDeniedException(messageSource.getMessage(
                        "insurance.link.forbidden", null, "You don't have permission to link insurance in this hospital", locale));
                }
            }

            var assignment = assignmentRepository
                .findFirstByUser_IdAndHospital_IdAndActiveTrue(actorUserId, hospitalId)
                .orElseThrow(() -> new BusinessException(messageSource.getMessage(
                    "assignment.required", null, "Valid assignment required", locale)));
            insurance.setAssignment(assignment);
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
        // ---- 0) Validate required natural key parts
        if (req == null || req.getPatientId() == null) {
            throw new BusinessException(messageSource.getMessage(
                "patientinsurance.patient.required", null, "Patient is required for insurance", locale));
        }
        if (req.getPayerCode() == null || req.getPayerCode().isBlank()) {
            throw new BusinessException(messageSource.getMessage(
                "patientinsurance.payer.required", null, "Payer code is required", locale));
        }
        if (req.getPolicyNumber() == null || req.getPolicyNumber().isBlank()) {
            throw new BusinessException(messageSource.getMessage(
                "patientinsurance.policy.required", null, "Policy number is required", locale));
        }

        final UUID patientId = req.getPatientId();
        final String payerCode = req.getPayerCode().trim();
        final String policyNumber = req.getPolicyNumber().trim();

        // ---- 1) Load patient & enforce patient-only self access (if caller is PATIENT-only)
        Patient patient = getPatientOrThrow(patientId, locale);
        enforceSelfAccessIfPatient(patient, locale);

        // ---- 2) Determine acting mode and (if STAFF) hospital/assignment + authorization
        final boolean actAsPatient = (ctx != null && ctx.mode() != null
            && "PATIENT".equalsIgnoreCase(ctx.mode().name()));
        final UUID actorUserId = (ctx != null && ctx.userId() != null)
            ? ctx.userId() : roleValidator.getCurrentUserId();

        UUID hospitalIdForStaff = null;

        if (actAsPatient) {
            // Patient path: only their own record; cannot set hospital or primary
            final UUID patientUserId = (patient.getUser() != null ? patient.getUser().getId() : null);
            if (patientUserId == null || !patientUserId.equals(actorUserId)) {
                throw new AccessDeniedException(messageSource.getMessage(
                    "access.denied.self", null, "You can only access your own insurance details", locale));
            }
            if (req.getHospitalId() != null) {
                throw new BusinessException(messageSource.getMessage(
                    "patient.cannot.link.hospital", null, "Patients cannot link insurance to a hospital", locale));
            }
            // FALL THROUGH with hospitalIdForStaff == null
        } else {
            // Staff/Admin path
            hospitalIdForStaff = (req.getHospitalId() != null) ? req.getHospitalId()
                : (ctx != null ? ctx.hospitalId() : null);

            if (hospitalIdForStaff == null) {
                throw new BusinessException(messageSource.getMessage(
                    "hospital.required", null, "Hospital context is required", locale));
            }

            final boolean hasChosenRole = ctx != null && ctx.roleCode() != null && !ctx.roleCode().isBlank();
            final boolean jwtGlobalOverride = roleValidator.isSuperAdminFromAuth()
                || roleValidator.isHospitalAdminFromAuthGlobalOnly();

            if (hasChosenRole) {
                roleValidator.validateRoleOrThrow(actorUserId, hospitalIdForStaff, ctx.roleCode().trim(), locale, messageSource);
            } else {
                final boolean hasScopedPermission = roleValidator.canLinkInsurance(actorUserId, hospitalIdForStaff);
                if (!hasScopedPermission && !jwtGlobalOverride) {
                    throw new AccessDeniedException(messageSource.getMessage(
                        "insurance.link.forbidden", null, "You don't have permission to link insurance in this hospital", locale));
                }
            }
        }

        // ---- 3) Upsert by natural key (patientId + payerCode + policyNumber)
        PatientInsurance insurance = patientInsuranceRepository
            .findByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(patientId, payerCode, policyNumber)
            .orElse(null);

        if (insurance == null) {
            // Create minimal insurance row; natural-key fields come from req
            insurance = new PatientInsurance();
            insurance.setPatient(patient);
            insurance.setPayerCode(payerCode);
            insurance.setPolicyNumber(policyNumber);
            // NOTE: Do NOT try to read non-existent fields from Link DTO (e.g., groupNumber)
        } else {
            // Ensure correct patient attached (safety, although finder already scoped by patient)
            insurance.setPatient(patient);
        }

        // ---- 4) Stamp assignment if STAFF; never in PATIENT mode
        if (!actAsPatient) {
            final var assignment = assignmentRepository
                .findFirstByUser_IdAndHospital_IdAndActiveTrue(actorUserId, hospitalIdForStaff)
                .orElseThrow(() -> new BusinessException(messageSource.getMessage(
                    "assignment.required", null, "Valid assignment required", locale)));
            insurance.setAssignment(assignment);
        }

        // ---- 5) Handle 'primary' only for STAFF + hospital-scoped primary
        // Patients cannot toggle primary; ignore req.getPrimary() if actAsPatient
        if (!actAsPatient && req.getPrimary() != null) {
            final boolean makePrimary = Boolean.TRUE.equals(req.getPrimary());
            if (makePrimary) {
                // Unset other primaries for this patient within the same hospital, then set this one to primary
                patientInsuranceRepository.unsetOtherPrimariesForPatientInHospital(patient.getId(), insurance.getId(), hospitalIdForStaff);
                insurance.setPrimary(true);
            } else {
                insurance.setPrimary(false);
            }
        }

        // Optionally track linkage meta if your entity has these fields
        insurance.setLinkedByUserId(actorUserId);
        insurance.setLinkedAs(actAsPatient ? "PATIENT" : "STAFF");

        PatientInsurance saved = patientInsuranceRepository.save(insurance);

        // If we set to primary=true *and* this insurance was just created (no ID in DB before),
        // you may want to call unsetOtherPrimaries... AFTER save to have a real ID.
        if (!actAsPatient && Boolean.TRUE.equals(req.getPrimary())) {
            patientInsuranceRepository.unsetOtherPrimariesForPatientInHospital(patient.getId(), saved.getId(), hospitalIdForStaff);
            saved.setPrimary(true);
            saved = patientInsuranceRepository.save(saved);
        }

        return patientInsuranceMapper.toPatientInsuranceResponseDTO(saved);
    }


}
