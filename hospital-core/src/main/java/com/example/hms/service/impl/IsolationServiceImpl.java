package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.IsolationMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Hospital;
import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.isolation.DiscontinuePrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.IsolationPrecautionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.IsolationService;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Isolation precautions (Tier 2 item 32).
 *
 * <p>See {@link IsolationService}. Tenancy follows the house 404-not-403
 * idiom: a precaution belonging to another hospital is indistinguishable
 * from one that does not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class IsolationServiceImpl implements IsolationService {

    private static final String MSG_NOT_FOUND = "isolation.precaution.notFound";

    private final Clock clock;
    private final IsolationPrecautionRepository precautionRepository;
    private final AdmissionRepository admissionRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final PatientChartAccess patientChartAccess;
    private final IsolationMapper mapper;
    private final RoleValidator roleValidator;

    @Override
    public IsolationPrecautionResponseDTO startPrecaution(IsolationPrecautionRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = patientChartAccess.require(request.getPatientId(), hospitalId);

        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessException(
                "A reason is required: the next clinician needs to know what they are guarding against.");
        }
        precautionRepository.findActiveOfType(patient.getId(), request.getPrecautionType())
            .ifPresent(existing -> {
                throw new BusinessException(
                    "This patient is already on %s precautions. Amend or discontinue the existing one."
                        .formatted(request.getPrecautionType()));
            });

        IsolationPrecaution precaution = IsolationPrecaution.builder()
            .hospital(hospitalRef(hospitalId))
            .patient(patient)
            .admission(resolveAdmission(request.getAdmissionId(), patient.getId(), hospitalId))
            .precautionType(request.getPrecautionType())
            .reason(request.getReason())
            .suspectedOrganism(request.getSuspectedOrganism())
            .startedAt(LocalDateTime.now(clock))
            .orderedBy(resolveStaff(request.getOrderedByStaffId(), hospitalId))
            .notes(request.getNotes())
            .build();

        IsolationPrecaution saved = precautionRepository.save(precaution);

        // Loud on purpose: an airborne precaution changes where the patient may
        // physically be placed, and the placement decision is made by people
        // who are not reading the chart.
        if (saved.requiresIsolationWard()) {
            log.warn("AIRBORNE isolation started for patient {} at hospital {} — bed placement is constrained",
                patient.getId(), hospitalId);
        }
        return mapper.toDto(saved);
    }

    @Override
    public IsolationPrecautionResponseDTO discontinuePrecaution(UUID precautionId,
                                                               DiscontinuePrecautionRequestDTO request) {
        if (!StringUtils.hasText(request.getDiscontinuationReason())) {
            throw new BusinessException(
                "A reason is required to lift a precaution — usually a negative result or a completed course.");
        }
        IsolationPrecaution precaution = loadScoped(precautionId);
        if (!precaution.isActive()) {
            throw new BusinessException("This precaution has already been discontinued.");
        }

        precaution.setEndedAt(LocalDateTime.now(clock));
        precaution.setDiscontinuationReason(request.getDiscontinuationReason());
        precaution.setDiscontinuedBy(
            resolveStaff(request.getDiscontinuedByStaffId(), precaution.getHospital().getId()));

        return mapper.toDto(precautionRepository.save(precaution));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IsolationPrecautionResponseDTO> getActiveForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return precautionRepository.findActiveForPatient(patientId).stream()
            .map(mapper::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IsolationPrecautionResponseDTO> getHistoryForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        return precautionRepository.findAllForPatient(patientId).stream()
            .map(mapper::toDto)
            .toList();
    }

    // ── Guards and helpers ──────────────────────────────────────────────

    /** 404-not-403 — another hospital's precaution reads as a missing one. */
    private IsolationPrecaution loadScoped(UUID precautionId) {
        IsolationPrecaution precaution = precautionRepository.findById(precautionId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND, precautionId));
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && precaution.getHospital() != null
            && !scope.equals(precaution.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_NOT_FOUND, precautionId);
        }
        return precaution;
    }

    /**
     * The admission is optional, but if one is named it must belong to this
     * patient at this hospital — otherwise a precaution could be hung off
     * somebody else's stay.
     */
    private Admission resolveAdmission(UUID admissionId, UUID patientId, UUID hospitalId) {
        if (admissionId == null) {
            return null;
        }
        Admission admission = admissionRepository.findById(admissionId)
            .orElseThrow(() -> new ResourceNotFoundException("admission.notFound", admissionId));
        boolean samePatient = admission.getPatient() != null
            && Objects.equals(admission.getPatient().getId(), patientId);
        boolean sameHospital = admission.getHospital() != null
            && Objects.equals(admission.getHospital().getId(), hospitalId);
        if (!samePatient || !sameHospital) {
            throw new ResourceNotFoundException("admission.notFound", admissionId);
        }
        return admission;
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException(
                "An active hospital is required: a precaution is nursed on a ward.");
        }
        return hospitalId;
    }

    private Hospital hospitalRef(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
    }

    private Staff resolveStaff(UUID staffId, UUID hospitalId) {
        if (staffId == null) {
            return null;
        }
        return staffRepository.findById(staffId)
            .filter(s -> s.getHospital() == null || Objects.equals(s.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
    }
}
