package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteAddendum;
import com.example.hms.model.NursingNoteEducationEntry;
import com.example.hms.model.NursingNoteInterventionEntry;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.nurse.NursingNoteAddendumResponseDTO;
import com.example.hms.payload.dto.nurse.NursingNoteEducationDTO;
import com.example.hms.payload.dto.nurse.NursingNoteInterventionDTO;
import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class NursingNoteMapper {

    public NursingNoteResponseDTO toResponse(NursingNote note) {
        if (note == null) {
            return null;
        }

        Patient patient = note.getPatient();
        Hospital hospital = note.getHospital();
        Staff staff = note.getAuthorStaff();
        User author = note.getAuthor();

        UUID hospitalId = hospital != null ? hospital.getId() : null;
        String patientMrn = resolvePatientMrn(patient, hospitalId);

        return NursingNoteResponseDTO.builder()
            .id(note.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(resolvePatientName(patient))
            .patientMrn(patientMrn)
            .hospitalId(hospitalId)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .authorUserId(author != null ? author.getId() : null)
            .authorStaffId(staff != null ? staff.getId() : null)
            .authorName(resolveAuthorName(note, staff, author))
            .authorCredentials(resolveAuthorCredentials(note, staff))
            .template(note.getTemplate())
            .dataSubjective(note.getDataSubjective())
            .dataObjective(note.getDataObjective())
            .dataAssessment(note.getDataAssessment())
            .dataPlan(note.getDataPlan())
            .dataImplementation(note.getDataImplementation())
            .dataEvaluation(note.getDataEvaluation())
            .actionSummary(note.getActionSummary())
            .responseSummary(note.getResponseSummary())
            .educationSummary(note.getEducationSummary())
            .narrative(note.getNarrative())
            .lateEntry(note.isLateEntry())
            .eventOccurredAt(toOffset(note.getEventOccurredAt()))
            .documentedAt(toOffset(note.getDocumentedAt()))
            .signedAt(toOffset(note.getSignedAt()))
            .signedByName(note.getSignedByName())
            .signedByCredentials(note.getSignedByCredentials())
            .attestAccuracy(note.isAttestAccuracy())
            .attestSpellCheck(note.isAttestSpellCheck())
            .attestNoAbbreviations(note.isAttestNoAbbreviations())
            .readabilityScore(note.getReadabilityScore())
            .createdAt(toOffset(note.getCreatedAt()))
            .updatedAt(toOffset(note.getUpdatedAt()))
            .educationEntries(mapEducationEntries(note.getEducationEntries()))
            .interventionEntries(mapInterventionEntries(note.getInterventionEntries()))
            .addenda(mapAddenda(note.getAddenda()))
            .build();
    }

    private List<NursingNoteEducationDTO> mapEducationEntries(List<NursingNoteEducationEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
            .filter(Objects::nonNull)
            .map(entry -> NursingNoteEducationDTO.builder()
                .topic(trim(entry.getTopic()))
                .teachingMethod(trim(entry.getTeachingMethod()))
                .patientUnderstanding(trim(entry.getPatientUnderstanding()))
                .reinforcementActions(trim(entry.getReinforcementActions()))
                .educationSummary(entry.getEducationSummary())
                .build())
            .toList();
    }

    private List<NursingNoteInterventionDTO> mapInterventionEntries(List<NursingNoteInterventionEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
            .filter(Objects::nonNull)
            .map(entry -> NursingNoteInterventionDTO.builder()
                .description(trim(entry.getDescription()))
                .linkedOrderId(entry.getLinkedOrderId())
                .linkedMedicationTaskId(entry.getLinkedMedicationTaskId())
                .followUpActions(trim(entry.getFollowUpActions()))
                .build())
            .toList();
    }

    private List<NursingNoteAddendumResponseDTO> mapAddenda(List<NursingNoteAddendum> addenda) {
        if (addenda == null || addenda.isEmpty()) {
            return List.of();
        }
        return addenda.stream()
            .filter(Objects::nonNull)
            .map(addendum -> NursingNoteAddendumResponseDTO.builder()
                .id(addendum.getId())
                .noteId(addendum.getNote() != null ? addendum.getNote().getId() : null)
                .authorUserId(addendum.getAuthor() != null ? addendum.getAuthor().getId() : null)
                .authorStaffId(addendum.getAuthorStaff() != null ? addendum.getAuthorStaff().getId() : null)
                .authorName(resolveAddendumAuthorName(addendum))
                .authorCredentials(resolveAddendumAuthorCredentials(addendum))
                .content(addendum.getContent())
                .eventOccurredAt(toOffset(addendum.getEventOccurredAt()))
                .documentedAt(toOffset(addendum.getDocumentedAt()))
                .signedAt(toOffset(addendum.getSignedAt()))
                .attestAccuracy(addendum.isAttestAccuracy())
                .attestNoAbbreviations(addendum.isAttestNoAbbreviations())
                .build())
            .toList();
    }

    private String resolvePatientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        String fullName = patient.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        User user = patient.getUser();
        if (user == null) {
            return null;
        }
        return joinNames(user.getFirstName(), user.getLastName());
    }

    private String resolvePatientMrn(Patient patient, UUID hospitalId) {
        if (patient == null || hospitalId == null) {
            return null;
        }
        try {
            return patient.getMrnForHospital(hospitalId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String resolveAuthorName(NursingNote note, Staff staff, User author) {
        if (note.getAuthorName() != null && !note.getAuthorName().isBlank()) {
            return note.getAuthorName();
        }
        if (staff != null) {
            String staffName = staff.getFullName();
            if (staffName != null && !staffName.isBlank()) {
                return staffName;
            }
            if (staff.getName() != null && !staff.getName().isBlank()) {
                return staff.getName();
            }
        }
        if (author != null) {
            String full = joinNames(author.getFirstName(), author.getLastName());
            if (full != null && !full.isBlank()) {
                return full;
            }
            if (author.getEmail() != null) {
                return author.getEmail();
            }
        }
        return "Unknown";
    }

    private String resolveAuthorCredentials(NursingNote note, Staff staff) {
        if (note.getAuthorCredentials() != null && !note.getAuthorCredentials().isBlank()) {
            return note.getAuthorCredentials();
        }
        if (staff != null) {
            if (staff.getJobTitle() != null) {
                return formatJobTitle(staff.getJobTitle().name());
            }
            if (staff.getLicenseNumber() != null) {
                return staff.getLicenseNumber();
            }
        }
        return null;
    }

    private String resolveAddendumAuthorName(NursingNoteAddendum addendum) {
        if (addendum.getAuthorName() != null && !addendum.getAuthorName().isBlank()) {
            return addendum.getAuthorName();
        }
        Staff staff = addendum.getAuthorStaff();
        if (staff != null) {
            String name = staff.getFullName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        User user = addendum.getAuthor();
        if (user != null) {
            return joinNames(user.getFirstName(), user.getLastName());
        }
        return "Unknown";
    }

    private String resolveAddendumAuthorCredentials(NursingNoteAddendum addendum) {
        if (addendum.getAuthorCredentials() != null && !addendum.getAuthorCredentials().isBlank()) {
            return addendum.getAuthorCredentials();
        }
        Staff staff = addendum.getAuthorStaff();
        if (staff != null && staff.getLicenseNumber() != null) {
            return staff.getLicenseNumber();
        }
        return null;
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private String joinNames(String first, String last) {
        String f = Optional.ofNullable(first).map(String::trim).orElse("");
        String l = Optional.ofNullable(last).map(String::trim).orElse("");
        String joined = (f + " " + l).trim();
        return joined.isEmpty() ? null : joined;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String formatJobTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        String[] parts = normalized.split(" ");
        return java.util.Arrays.stream(parts)
            .filter(s -> !s.isBlank())
            .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
            .collect(Collectors.joining(" "));
    }
}
