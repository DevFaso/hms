package com.example.hms.mapper;

import com.example.hms.enums.EncounterNoteLinkType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.EncounterWorkspaceVisitType;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.EncounterLinkedArtifactDTO;
import com.example.hms.payload.dto.EncounterNoteAddendumResponseDTO;
import com.example.hms.payload.dto.EncounterNoteRequestDTO;
import com.example.hms.payload.dto.EncounterNoteResponseDTO;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceAddendumDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceCreateRequestDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceDetailDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceNoteDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceNoteUpdateRequestDTO;
import com.example.hms.payload.dto.encounter.workspace.EncounterWorkspaceSectionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EncounterWorkspaceMapper {

    private static final Map<EncounterWorkspaceVisitType, EncounterType> VISIT_TO_ENCOUNTER = Map.of(
        EncounterWorkspaceVisitType.OFFICE, EncounterType.OUTPATIENT,
        EncounterWorkspaceVisitType.TELEHEALTH, EncounterType.FOLLOW_UP,
        EncounterWorkspaceVisitType.INPATIENT, EncounterType.INPATIENT
    );
    private static final String SECTION_SUBJECTIVE = "SUBJECTIVE";
    private static final String SECTION_OBJECTIVE = "OBJECTIVE";
    private static final String SECTION_ASSESSMENT = "ASSESSMENT";
    private static final String SECTION_PLAN = "PLAN";
    private static final String SECTION_INTERVENTIONS = "INTERVENTIONS";
    private static final String SECTION_EVALUATION = "EVALUATION";

    public EncounterWorkspaceDetailDTO toWorkspaceDetail(EncounterResponseDTO encounter,
                                                         EncounterWorkspaceNoteDTO note,
                                                         EncounterWorkspaceVisitType visitTypeOverride) {
        if (encounter == null) {
            return null;
        }
        return EncounterWorkspaceDetailDTO.builder()
            .id(encounter.getId())
            .patientId(encounter.getPatientId())
            .patientName(encounter.getPatientName())
            .encounterType(encounter.getEncounterType())
            .status(encounter.getStatus())
            .visitType(visitTypeOverride)
            .startTime(encounter.getEncounterDate())
            .department(encounter.getDepartmentName())
            .providerId(encounter.getStaffId() != null ? encounter.getStaffId().toString() : null)
            .providerName(encounter.getStaffName())
            .chiefComplaint(encounter.getNotes())
            .structuredNotes(note)
            .build();
    }

    @SuppressWarnings("java:S1172") // visitType reserved for future visit-type-specific note formatting
    public EncounterWorkspaceNoteDTO toWorkspaceNote(UUID encounterId,
                                                     EncounterNoteResponseDTO note,
                                                     EncounterWorkspaceVisitType visitType) {
        if (note == null) {
            return null;
        }
        return EncounterWorkspaceNoteDTO.builder()
            .encounterId(encounterId)
            .template(note.getTemplate())
            .sections(buildSections(note))
            .linkedOrderIds(extractLinkedIds(note, EncounterNoteLinkType.LAB_ORDER))
            .linkedPrescriptionIds(extractLinkedIds(note, EncounterNoteLinkType.PRESCRIPTION))
            .linkedReferralIds(extractLinkedIds(note, EncounterNoteLinkType.REFERRAL))
            .documentationDateTime(note.getDocumentedAt())
            .eventDateTime(note.getEventOccurredAt())
            .lateEntry(note.getLateEntry())
            .lastUpdatedAt(note.getUpdatedAt())
            .lastUpdatedBy(note.getSignedByName())
            .addendums(buildAddendums(note.getAddenda()))
            .build();
    }

    @SuppressWarnings("java:S1172") // locale and encounterId reserved for future i18n/context-aware mapping
    public EncounterRequestDTO toEncounterRequest(UUID patientId,
                                                  UUID hospitalId,
                                                  UUID departmentId,
                                                  Staff staff,
                                                  EncounterWorkspaceCreateRequestDTO request,
                                                  Locale locale) {
        EncounterNoteRequestDTO note = EncounterNoteRequestDTO.builder()
            .template(request.getNoteTemplate())
            .chiefComplaint(request.getChiefComplaint())
            .historyOfPresentIllness(request.getHistoryOfPresentIllness())
            .subjective(sectionContent(request, SECTION_SUBJECTIVE))
            .objective(sectionContent(request, SECTION_OBJECTIVE))
            .assessment(sectionContent(request, SECTION_ASSESSMENT))
            .plan(sectionContent(request, SECTION_PLAN))
            .implementation(sectionContent(request, SECTION_INTERVENTIONS))
            .evaluation(sectionContent(request, SECTION_EVALUATION))
            .authorStaffId(staff != null ? staff.getId() : null)
            .authorUserId(staff != null && staff.getUser() != null ? staff.getUser().getId() : null)
            .authorName(staff != null ? staff.getFullName() : null)
            .documentedAt(request.getStartTime())
            .linkedLabOrderIds(parseIds(request.getLinkedOrderIds()))
            .linkedPrescriptionIds(parseIds(request.getLinkedPrescriptionIds()))
            .linkedReferralIds(parseIds(request.getLinkedReferralIds()))
            .build();

        return EncounterRequestDTO.builder()
            .patientId(patientId)
            .staffId(staff != null ? staff.getId() : null)
            .hospitalId(hospitalId)
            .departmentId(departmentId)
            .encounterType(mapVisitType(request.getVisitType()))
            .encounterDate(request.getStartTime())
            .notes(request.getHistoryOfPresentIllness())
            .note(note)
            .status(EncounterStatus.IN_PROGRESS)
            .build();
    }

    @SuppressWarnings("java:S1172") // encounterId reserved for future encounter-linked note validation
    public EncounterNoteRequestDTO toEncounterNoteUpdate(UUID encounterId,
                                                         Staff staff,
                                                         EncounterWorkspaceNoteUpdateRequestDTO request) {
        EncounterNoteRequestDTO.EncounterNoteRequestDTOBuilder<?, ?> builder = EncounterNoteRequestDTO.builder()
            .template(request.getNoteTemplate())
            .subjective(sectionContent(request.getSections(), SECTION_SUBJECTIVE))
            .objective(sectionContent(request.getSections(), SECTION_OBJECTIVE))
            .assessment(sectionContent(request.getSections(), SECTION_ASSESSMENT))
            .plan(sectionContent(request.getSections(), SECTION_PLAN))
            .implementation(sectionContent(request.getSections(), SECTION_INTERVENTIONS))
            .evaluation(sectionContent(request.getSections(), SECTION_EVALUATION))
            .authorStaffId(staff != null ? staff.getId() : null)
            .authorUserId(staff != null && staff.getUser() != null ? staff.getUser().getId() : null)
            .documentedAt(request.getDocumentationDateTime())
            .eventOccurredAt(request.getEventDateTime())
            .lateEntry(Boolean.TRUE.equals(request.getLateEntry()))
            .linkedLabOrderIds(parseIds(request.getLinkedOrderIds()))
            .linkedPrescriptionIds(parseIds(request.getLinkedPrescriptionIds()))
            .linkedReferralIds(parseIds(request.getLinkedReferralIds()));

        return builder.build();
    }

    private EncounterType mapVisitType(EncounterWorkspaceVisitType visitType) {
        return VISIT_TO_ENCOUNTER.getOrDefault(visitType, EncounterType.CONSULTATION);
    }

    private List<EncounterWorkspaceSectionDTO> buildSections(EncounterNoteResponseDTO note) {
        List<EncounterWorkspaceSectionDTO> sections = new ArrayList<>();
        addSection(sections, SECTION_SUBJECTIVE, "Subjective history", note.getSubjective());
        addSection(sections, SECTION_OBJECTIVE, "Objective findings", note.getObjective());
        addSection(sections, SECTION_ASSESSMENT, "Assessment", note.getAssessment());
        addSection(sections, SECTION_PLAN, "Plan", note.getPlan());
        addSection(sections, SECTION_INTERVENTIONS, "Interventions", note.getImplementation());
        addSection(sections, SECTION_EVALUATION, "Evaluation", note.getEvaluation());
        return sections;
    }

    private void addSection(List<EncounterWorkspaceSectionDTO> sections,
                            String key,
                            String label,
                            String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sections.add(EncounterWorkspaceSectionDTO.builder()
            .key(key)
            .label(label)
            .content(content)
            .build());
    }

    private List<UUID> extractLinkedIds(EncounterNoteResponseDTO note, EncounterNoteLinkType type) {
        if (note == null || note.getLinkedArtifacts() == null) {
            return List.of();
        }
        return note.getLinkedArtifacts().stream()
            .filter(artifact -> artifact != null && artifact.getArtifactType() == type)
            .map(EncounterLinkedArtifactDTO::getArtifactId)
            .filter(Objects::nonNull)
            .toList();
    }

    private List<EncounterWorkspaceAddendumDTO> buildAddendums(List<EncounterNoteAddendumResponseDTO> addenda) {
        if (addenda == null) {
            return List.of();
        }
        return addenda.stream()
            .map(addendum -> EncounterWorkspaceAddendumDTO.builder()
                .id(addendum.getId())
                .content(addendum.getContent())
                .authorName(addendum.getAuthorName())
                .authorCredentials(addendum.getAuthorCredentials())
                .eventDateTime(addendum.getEventOccurredAt())
                .documentationDateTime(addendum.getDocumentedAt())
                .createdAt(addendum.getCreatedAt())
                .lateEntry(Boolean.TRUE.equals(addendum.getLateEntry()))
                .build())
            .toList();
    }

    private String sectionContent(EncounterWorkspaceCreateRequestDTO request, String key) {
        return sectionContent(request.getSections(), key);
    }

    private String sectionContent(List<EncounterWorkspaceSectionDTO> sections, String key) {
        if (sections == null) {
            return null;
        }
        return sections.stream()
            .filter(section -> section != null && key.equalsIgnoreCase(section.getKey()))
            .map(EncounterWorkspaceSectionDTO::getContent)
            .filter(content -> content != null && !content.isBlank())
            .findFirst()
            .orElse(null);
    }

    private java.util.Set<UUID> parseIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Set.of();
        }
        return rawIds.stream()
            .map(this::safeParseUuid)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private UUID safeParseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
