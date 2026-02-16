package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientChartUpdateMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.chart.PatientChartAttachment;
import com.example.hms.model.chart.PatientChartSectionEntry;
import com.example.hms.model.chart.PatientChartUpdate;
import com.example.hms.payload.dto.DoctorChartAttachmentDTO;
import com.example.hms.payload.dto.DoctorChartSectionDTO;
import com.example.hms.payload.dto.DoctorPatientChartUpdateRequestDTO;
import com.example.hms.payload.dto.PatientChartUpdateResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientChartUpdateRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientChartUpdateServiceImpl implements PatientChartUpdateService {

    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientChartUpdateRepository patientChartUpdateRepository;
    private final StaffRepository staffRepository;
    private final PatientChartUpdateMapper patientChartUpdateMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<PatientChartUpdateResponseDTO> listPatientChartUpdates(UUID patientId, UUID hospitalId, Pageable pageable) {
        validatePatientHospitalContext(patientId, hospitalId);
        return patientChartUpdateRepository
            .findByPatient_IdAndHospital_Id(patientId, hospitalId, pageable)
            .map(patientChartUpdateMapper::toResponseDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientChartUpdateResponseDTO getPatientChartUpdate(UUID patientId, UUID hospitalId, UUID updateId) {
        validatePatientHospitalContext(patientId, hospitalId);
        PatientChartUpdate update = patientChartUpdateRepository.findById(updateId)
            .filter(entity -> Objects.equals(entity.getPatient().getId(), patientId))
            .filter(entity -> Objects.equals(entity.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("Chart update not found for patient and hospital context."));
        return patientChartUpdateMapper.toResponseDto(update);
    }

    @Override
    @Transactional
    public PatientChartUpdateResponseDTO createPatientChartUpdate(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        DoctorPatientChartUpdateRequestDTO request
    ) {
        if (request == null) {
            throw new BusinessException("Chart update payload is required.");
        }
        if (request.getHospitalId() == null && hospitalId == null) {
            throw new BusinessException("Hospital context is required for chart updates.");
        }
        UUID effectiveHospitalId = hospitalId != null ? hospitalId : request.getHospitalId();
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context could not be resolved for chart update request.");
        }
        validatePatientHospitalContext(patientId, effectiveHospitalId);

        if (assignment == null || assignment.getHospital() == null
            || !Objects.equals(assignment.getHospital().getId(), effectiveHospitalId)) {
            throw new BusinessException("Hospital assignment does not match requested context.");
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientId));
        Hospital hospital = hospitalRepository.findById(effectiveHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + effectiveHospitalId));

        Staff staff = staffRepository.findByUserIdAndHospitalId(requesterUserId, effectiveHospitalId)
            .orElseThrow(() -> new BusinessException("Staff profile not found for authenticated user in hospital."));

        String updateReason = trim(request.getUpdateReason());
        if (updateReason == null) {
            throw new BusinessException("Update reason is required for chart updates.");
        }

        int nextVersion = patientChartUpdateRepository
            .findTopByPatient_IdAndHospital_IdOrderByVersionNumberDesc(patientId, effectiveHospitalId)
            .map(PatientChartUpdate::getVersionNumber)
            .map(v -> v + 1)
            .orElse(1);

        PatientChartUpdate update = PatientChartUpdate.builder()
            .patient(patient)
            .hospital(hospital)
            .recordedBy(staff)
            .assignment(assignment)
            .versionNumber(nextVersion)
            .updateReason(updateReason)
            .summary(trim(request.getSummary()))
            .includeSensitive(Boolean.TRUE.equals(request.getIncludeSensitiveData()))
            .notifyCareTeam(Boolean.TRUE.equals(request.getNotifyCareTeam()))
            .sections(mapSections(request.getSections()))
            .attachments(mapAttachments(request.getAttachments()))
            .build();

        PatientChartUpdate saved = patientChartUpdateRepository.save(update);
        log.info("Saved chart update version {} for patient {} in hospital {}", nextVersion, patientId, effectiveHospitalId);
        return patientChartUpdateMapper.toResponseDto(saved);
    }

    private void validatePatientHospitalContext(UUID patientId, UUID hospitalId) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital identifier is required for chart updates.");
        }
        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found: " + patientId);
        }
        if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)) {
            throw new BusinessException("Patient is not registered in the specified hospital.");
        }
    }

    private List<PatientChartSectionEntry> mapSections(List<DoctorChartSectionDTO> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        return sections.stream()
            .filter(Objects::nonNull)
            .map(section -> PatientChartSectionEntry.builder()
                .sectionType(section.getSectionType())
                .code(trim(section.getCode()))
                .display(trim(section.getDisplay()))
                .narrative(trim(section.getNarrative()))
                .status(trim(section.getStatus()))
                .severity(trim(section.getSeverity()))
                .sourceSystem(trim(section.getSourceSystem()))
                .occurredOn(section.getOccurredOn())
                .linkedResourceId(section.getLinkedResourceId())
                .sensitive(section.getSensitive())
                .authorNotes(trim(section.getAuthorNotes()))
                .detailsJson(serializeDetails(section.getDetails()))
                .build())
            .toList();
    }

    private List<PatientChartAttachment> mapAttachments(List<DoctorChartAttachmentDTO> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
            .filter(Objects::nonNull)
            .map(attachment -> PatientChartAttachment.builder()
                .storageKey(trim(attachment.getStorageKey()))
                .fileName(trim(attachment.getFileName()))
                .contentType(trim(attachment.getContentType()))
                .sizeBytes(attachment.getSizeBytes())
                .sha256(trim(attachment.getSha256()))
                .label(trim(attachment.getLabel()))
                .category(trim(attachment.getCategory()))
                .build())
            .toList();
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String serializeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize chart section details", e);
            throw new BusinessException("Unable to process structured chart details.");
        }
    }
}
