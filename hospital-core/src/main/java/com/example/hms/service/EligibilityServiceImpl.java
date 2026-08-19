package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.User;
import com.example.hms.model.insurance.EligibilityCheck;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityCheckRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityResponseDTO;
import com.example.hms.repository.EligibilityCheckRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.integration.eligibility.EligibilityProvider;
import com.example.hms.service.integration.eligibility.EligibilityProviderRequest;
import com.example.hms.service.integration.eligibility.EligibilityProviderResult;
import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link EligibilityService}.
 *
 * <p>Resolution order: the first {@link EligibilityProvider} bean whose
 * {@link EligibilityProvider#supports(EligibilityScheme)} returns {@code true}
 * for the requested scheme wins. The
 * {@link com.example.hms.service.integration.eligibility.StubEligibilityProvider}
 * matches everything, so it acts as the default fallback when no scheme-specific
 * connector is registered.
 */
@Service
@Slf4j
public class EligibilityServiceImpl implements EligibilityService {

    private final EligibilityCheckRepository checkRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientInsuranceRepository patientInsuranceRepository;
    private final UserRepository userRepository;
    private final AuditEventLogService auditService;
    private final List<EligibilityProvider> providers;
    private final Clock clock;
    private final IntegrationHealthRecorder healthRecorder;

    public EligibilityServiceImpl(EligibilityCheckRepository checkRepository,
                                  PatientRepository patientRepository,
                                  HospitalRepository hospitalRepository,
                                  PatientInsuranceRepository patientInsuranceRepository,
                                  UserRepository userRepository,
                                  AuditEventLogService auditService,
                                  List<EligibilityProvider> providers,
                                  Clock clock,
                                  IntegrationHealthRecorder healthRecorder) {
        this.checkRepository = checkRepository;
        this.patientRepository = patientRepository;
        this.hospitalRepository = hospitalRepository;
        this.patientInsuranceRepository = patientInsuranceRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.providers = providers;
        this.clock = clock;
        this.healthRecorder = healthRecorder;
    }

    @Override
    @Transactional
    public EligibilityResponseDTO submit(EligibilityCheckRequestDTO request) {
        if (request == null) {
            throw new BusinessException("EligibilityCheckRequest is required");
        }
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Patient not found: " + request.getPatientId()));
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + request.getHospitalId()));
        PatientInsurance insurance = null;
        if (request.getPatientInsuranceId() != null) {
            insurance = patientInsuranceRepository.findById(request.getPatientInsuranceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "PatientInsurance not found: " + request.getPatientInsuranceId()));
            if (insurance.getPatient() != null
                && !insurance.getPatient().getId().equals(patient.getId())) {
                throw new BusinessException(
                    "PatientInsurance does not belong to the requested patient");
            }
        }
        EligibilityProvider provider = providers.stream()
            .filter(p -> p.supports(request.getScheme()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "No eligibility provider configured for scheme " + request.getScheme()));

        LocalDateTime requestedAt = LocalDateTime.now(clock);
        User caller = currentUserOrNull();

        EligibilityCheck check = EligibilityCheck.builder()
            .patient(patient)
            .hospital(hospital)
            .patientInsurance(insurance)
            .scheme(request.getScheme())
            .checkType(request.getCheckType())
            .memberId(trimToNull(request.getMemberId()))
            .serviceCode(trimToNull(request.getServiceCode()))
            .requestedAt(requestedAt)
            .status(EligibilityStatus.PENDING)
            .requestedBy(caller)
            .build();

        EligibilityProviderRequest providerRequest = EligibilityProviderRequest.builder()
            .patientId(patient.getId())
            .hospitalId(hospital.getId())
            .scheme(request.getScheme())
            .memberId(check.getMemberId())
            .serviceCode(check.getServiceCode())
            .build();

        EligibilityProviderResult result;
        try {
            result = (request.getCheckType() == EligibilityCheckType.PRIOR_AUTH)
                ? provider.requestPriorAuth(providerRequest)
                : provider.checkCoverage(providerRequest);
        } catch (RuntimeException ex) {
            log.warn("Eligibility provider {} threw for scheme={} patient={}: {}",
                provider.getClass().getSimpleName(), request.getScheme(), patient.getId(),
                ex.getMessage());
            result = EligibilityProviderResult.builder()
                .status(EligibilityStatus.ERROR)
                .errorMessage(ex.getMessage())
                .build();
        }

        applyResult(check, result);
        check.setCompletedAt(LocalDateTime.now(clock));
        EligibilityCheck saved = checkRepository.save(check);

        recordIntegrationHealth(hospital, saved.getStatus(), result);
        emitAudit(caller, hospital, patient, saved);
        return toDto(saved);
    }

    private void recordIntegrationHealth(Hospital hospital,
                                         EligibilityStatus status,
                                         EligibilityProviderResult result) {
        UUID organizationId = hospital != null && hospital.getOrganization() != null
            ? hospital.getOrganization().getId()
            : null;
        boolean failed = status == EligibilityStatus.ERROR
            || status == EligibilityStatus.UNKNOWN;
        if (failed) {
            String message = result != null && result.getErrorMessage() != null
                ? result.getErrorMessage()
                : "Eligibility provider returned " + status;
            healthRecorder.recordFailure(
                SuperAdminIntegrationHealthService.INTEGRATION_ID_ELIGIBILITY,
                organizationId,
                message,
                null);
        } else {
            healthRecorder.recordSuccess(
                SuperAdminIntegrationHealthService.INTEGRATION_ID_ELIGIBILITY,
                organizationId,
                null);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EligibilityResponseDTO get(UUID checkId) {
        return checkRepository.findById(checkId)
            .map(this::toDto)
            .orElseThrow(() -> new ResourceNotFoundException(
                "EligibilityCheck not found: " + checkId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EligibilityResponseDTO> listByPatient(UUID patientId, UUID hospitalId, Pageable pageable) {
        Page<EligibilityCheck> page = (hospitalId == null)
            ? checkRepository.findByPatient_IdOrderByRequestedAtDesc(patientId, pageable)
            : checkRepository.findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(
                patientId, hospitalId, pageable);
        return page.map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EligibilityResponseDTO> findLatestForPatient(UUID patientId,
                                                                 UUID hospitalId,
                                                                 EligibilityScheme scheme,
                                                                 EligibilityCheckType type) {
        Optional<EligibilityCheck> hit = (hospitalId == null)
            ? checkRepository.findFirstByPatient_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc(
                patientId, scheme, type)
            : checkRepository.findFirstByPatient_IdAndHospital_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc(
                patientId, hospitalId, scheme, type);
        return hit.map(this::toDto);
    }

    // ─────────────────────────────────────────────────────────────────────

    private void applyResult(EligibilityCheck target, EligibilityProviderResult result) {
        target.setStatus(result.getStatus() != null ? result.getStatus() : EligibilityStatus.UNKNOWN);
        target.setResponseCode(result.getResponseCode());
        target.setPayerResponseText(result.getPayerResponseText());
        target.setCopayAmount(result.getCopayAmount());
        target.setCopayCurrency(result.getCopayCurrency());
        target.setPriorAuthRequired(result.getPriorAuthRequired());
        target.setPriorAuthNumber(result.getPriorAuthNumber());
        target.setValidUntil(result.getValidUntil());
        target.setErrorMessage(result.getErrorMessage());
    }

    private void emitAudit(User caller, Hospital hospital, Patient patient, EligibilityCheck check) {
        try {
            AuditEventRequestDTO event = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_ACCESS)
                .status(check.getStatus() == EligibilityStatus.ERROR
                    ? AuditStatus.FAILURE : AuditStatus.SUCCESS)
                .entityType("EligibilityCheck")
                .resourceId(check.getId() != null ? check.getId().toString() : null)
                .userId(caller != null ? caller.getId() : null)
                .userName(caller != null ? caller.getUsername() : null)
                .hospitalName(hospital.getName())
                .resourceName(patient.getId() != null ? patient.getId().toString() : null)
                .eventDescription("Eligibility " + check.getCheckType()
                    + " against scheme " + check.getScheme()
                    + " — status=" + check.getStatus()
                    + (check.getResponseCode() != null ? " (" + check.getResponseCode() + ")" : ""))
                .build();
            auditService.logEvent(event);
        } catch (RuntimeException ex) {
            // Audit failure must never break the clinical flow
            log.warn("Failed to emit audit for EligibilityCheck {}: {}",
                check.getId(), ex.getMessage());
        }
    }

    private User currentUserOrNull() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(username).orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private EligibilityResponseDTO toDto(EligibilityCheck check) {
        return EligibilityResponseDTO.builder()
            .id(check.getId())
            .patientId(check.getPatient() != null ? check.getPatient().getId() : null)
            .hospitalId(check.getHospital() != null ? check.getHospital().getId() : null)
            .patientInsuranceId(check.getPatientInsurance() != null
                ? check.getPatientInsurance().getId() : null)
            .scheme(check.getScheme())
            .checkType(check.getCheckType())
            .memberId(check.getMemberId())
            .serviceCode(check.getServiceCode())
            .requestedAt(check.getRequestedAt())
            .completedAt(check.getCompletedAt())
            .status(check.getStatus())
            .responseCode(check.getResponseCode())
            .payerResponseText(check.getPayerResponseText())
            .copayAmount(check.getCopayAmount())
            .copayCurrency(check.getCopayCurrency())
            .priorAuthRequired(check.getPriorAuthRequired())
            .priorAuthNumber(check.getPriorAuthNumber())
            .validUntil(check.getValidUntil())
            .errorMessage(check.getErrorMessage())
            .requestedByUserId(check.getRequestedBy() != null ? check.getRequestedBy().getId() : null)
            .build();
    }

}
