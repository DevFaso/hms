package com.example.hms.mapper;

import com.example.hms.model.chart.PatientChartAttachment;
import com.example.hms.model.chart.PatientChartSectionEntry;
import com.example.hms.model.chart.PatientChartUpdate;
import com.example.hms.payload.dto.PatientChartAttachmentResponseDTO;
import com.example.hms.payload.dto.PatientChartSectionEntryResponseDTO;
import com.example.hms.payload.dto.PatientChartUpdateResponseDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PatientChartUpdateMapper {

    private final ObjectMapper objectMapper;

    public PatientChartUpdateResponseDTO toResponseDto(PatientChartUpdate update) {
        if (update == null) {
            return null;
        }

        UUID patientId = update.getPatient() != null ? update.getPatient().getId() : null;
        UUID hospitalId = update.getHospital() != null ? update.getHospital().getId() : null;
        UUID staffId = update.getRecordedBy() != null ? update.getRecordedBy().getId() : null;
        String recordedByName = update.getRecordedBy() != null ? update.getRecordedBy().getFullName() : null;
        String recordedByRole = update.getAssignment() != null && update.getAssignment().getRole() != null
            ? update.getAssignment().getRole().getCode()
            : null;

        return PatientChartUpdateResponseDTO.builder()
            .id(update.getId())
            .patientId(patientId)
            .hospitalId(hospitalId)
            .versionNumber(update.getVersionNumber())
            .updateReason(update.getUpdateReason())
            .summary(update.getSummary())
            .includeSensitive(update.isIncludeSensitive())
            .notifyCareTeam(update.isNotifyCareTeam())
            .sectionCount(update.getSectionCount())
            .attachmentCount(update.getAttachmentCount())
            .recordedAt(update.getCreatedAt())
            .recordedByStaffId(staffId)
            .recordedByName(recordedByName)
            .recordedByRole(recordedByRole)
        .sections(mapSections(update.getSections(), update.getId()))
            .attachments(mapAttachments(update.getAttachments()))
            .build();
    }

    private List<PatientChartSectionEntryResponseDTO> mapSections(List<PatientChartSectionEntry> sections, UUID chartUpdateId) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        List<PatientChartSectionEntryResponseDTO> results = new ArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            PatientChartSectionEntry source = sections.get(i);
            results.add(PatientChartSectionEntryResponseDTO.builder()
                .id(chartUpdateId != null ? chartUpdateId + "-section-" + i : null)
                .orderIndex(i)
                .sectionType(source.getSectionType())
                .code(source.getCode())
                .display(source.getDisplay())
                .narrative(source.getNarrative())
                .status(source.getStatus())
                .severity(source.getSeverity())
                .sourceSystem(source.getSourceSystem())
                .occurredOn(source.getOccurredOn())
                .linkedResourceId(source.getLinkedResourceId())
                .sensitive(source.getSensitive())
                .authorNotes(source.getAuthorNotes())
                .details(parseDetails(source.getDetailsJson()))
                .build());
        }
        return results;
    }

    private Map<String, Object> parseDetails(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(detailsJson, new TypeReference<>() {});
        } catch (IOException e) {
            log.warn("Unable to parse chart section details JSON payload", e);
            return Map.of();
        }
    }

    private List<PatientChartAttachmentResponseDTO> mapAttachments(List<PatientChartAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<PatientChartAttachmentResponseDTO> results = new ArrayList<>(attachments.size());
        for (PatientChartAttachment attachment : attachments) {
            results.add(PatientChartAttachmentResponseDTO.builder()
                .storageKey(attachment.getStorageKey())
                .fileName(attachment.getFileName())
                .contentType(attachment.getContentType())
                .sizeBytes(attachment.getSizeBytes())
                .sha256(attachment.getSha256())
                .label(attachment.getLabel())
                .category(attachment.getCategory())
                .build());
        }
        return results;
    }
}
