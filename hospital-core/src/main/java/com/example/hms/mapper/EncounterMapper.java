package com.example.hms.mapper;

import com.example.hms.enums.EncounterType;
import com.example.hms.model.*;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.model.encounter.EncounterNoteAddendum;
import com.example.hms.model.encounter.EncounterNoteHistory;
import com.example.hms.model.encounter.EncounterNoteLink;
import com.example.hms.payload.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Component
public class EncounterMapper {

    public EncounterResponseDTO toEncounterResponseDTO(Encounter e) {
        if (e == null) return null;

        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(e.getId());
        dto.setEncounterType(e.getEncounterType());
        dto.setEncounterDate(e.getEncounterDate());
        dto.setStatus(e.getStatus());
        dto.setNotes(nullSafe(e.getNotes()));
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());

        mapPatient(dto, e.getPatient());
        mapStaff(dto, e.getStaff());
        mapDepartment(dto, e.getDepartment());
        mapHospital(dto, e.getHospital());
        mapAppointment(dto, e.getAppointment());

        dto.setNote(toEncounterNoteResponseDTO(e.getEncounterNote()));

        return dto;
    }

    private void mapPatient(EncounterResponseDTO dto, Patient p) {
        if (p == null) return;
        dto.setPatientId(p.getId());
        dto.setPatientName(joinName(p.getFirstName(), p.getLastName()));
        dto.setPatientEmail(nullSafe(p.getEmail()));
        dto.setPatientPhoneNumber(nullSafe(p.getPhoneNumberPrimary()));
    }

    private void mapStaff(EncounterResponseDTO dto, Staff s) {
        if (s == null) return;
        dto.setStaffId(s.getId());
        String staffDisplay = (s.getUser() != null)
            ? joinName(s.getUser().getFirstName(), s.getUser().getLastName())
            : nullSafe(s.getName());
        dto.setStaffName(staffDisplay);
        dto.setStaffEmail(s.getUser() != null ? nullSafe(s.getUser().getEmail()) : null);
        dto.setStaffPhoneNumber(s.getUser() != null ? nullSafe(s.getUser().getPhoneNumber()) : null);
    }

    private void mapDepartment(EncounterResponseDTO dto, Department d) {
        if (d == null) return;
        dto.setDepartmentId(d.getId());
        dto.setDepartmentName(nullSafe(d.getName()));
    }

    private void mapHospital(EncounterResponseDTO dto, Hospital h) {
        if (h == null) return;
        dto.setHospitalId(h.getId());
        dto.setHospitalName(nullSafe(h.getName()));
        dto.setHospitalAddress(nullSafe(h.getAddress()));
        dto.setHospitalEmail(nullSafe(h.getEmail()));
        dto.setHospitalPhoneNumber(nullSafe(h.getPhoneNumber()));
    }

    private void mapAppointment(EncounterResponseDTO dto, Appointment a) {
        if (a == null) return;
        dto.setAppointmentId(a.getId());
        dto.setAppointmentReason(nullSafe(a.getReason()));
        dto.setAppointmentNotes(nullSafe(a.getNotes()));
        dto.setAppointmentStatus(a.getStatus() != null ? a.getStatus().name() : null);
        dto.setAppointmentType(null);

        LocalDate date = a.getAppointmentDate();
        LocalTime start = a.getStartTime();
        dto.setAppointmentDate((date != null && start != null)
            ? LocalDateTime.of(date, start)
            : null);
    }

    public EncounterNoteResponseDTO toEncounterNoteResponseDTO(EncounterNote note) {
        if (note == null) {
            return null;
        }
        return EncounterNoteResponseDTO.builder()
            .id(note.getId())
            .template(note.getTemplate())
            .chiefComplaint(nullSafe(note.getChiefComplaint()))
            .historyOfPresentIllness(nullSafe(note.getHistoryOfPresentIllness()))
            .reviewOfSystems(nullSafe(note.getReviewOfSystems()))
            .physicalExam(nullSafe(note.getPhysicalExam()))
            .diagnosticResults(nullSafe(note.getDiagnosticResults()))
            .subjective(nullSafe(note.getSubjective()))
            .objective(nullSafe(note.getObjective()))
            .assessment(nullSafe(note.getAssessment()))
            .plan(nullSafe(note.getPlan()))
            .implementation(nullSafe(note.getImplementation()))
            .evaluation(nullSafe(note.getEvaluation()))
            .patientInstructions(nullSafe(note.getPatientInstructions()))
            .summary(nullSafe(note.getSummary()))
            .lateEntry(note.isLateEntry())
            .eventOccurredAt(note.getEventOccurredAt())
            .documentedAt(note.getDocumentedAt())
            .updatedAt(note.getUpdatedAt())
            .attestAccuracy(note.isAttestAccuracy())
            .attestNoAbbreviations(note.isAttestNoAbbreviations())
            .attestSpellCheck(note.isAttestSpellCheck())
            .signedAt(note.getSignedAt())
            .signedByName(nullSafe(note.getSignedByName()))
            .signedByCredentials(nullSafe(note.getSignedByCredentials()))
            .addenda(note.getAddenda() != null
                ? note.getAddenda().stream()
                    .map(this::toEncounterNoteAddendumResponseDTO)
                    .toList()
                : null)
            .linkedArtifacts(note.getLinks() != null
                ? note.getLinks().stream().map(this::toEncounterLinkedArtifactDTO).toList()
                : null)
            .build();
    }

    private EncounterNoteAddendumResponseDTO toEncounterNoteAddendumResponseDTO(EncounterNoteAddendum addendum) {
        if (addendum == null) {
            return null;
        }
        boolean lateEntry = addendum.getEventOccurredAt() != null
            && addendum.getDocumentedAt() != null
            && addendum.getDocumentedAt().isAfter(addendum.getEventOccurredAt());
        return EncounterNoteAddendumResponseDTO.builder()
            .id(addendum.getId())
            .content(addendum.getContent())
            .eventOccurredAt(addendum.getEventOccurredAt())
            .documentedAt(addendum.getDocumentedAt())
            .signedAt(addendum.getSignedAt())
            .createdAt(addendum.getCreatedAt())
            .authorName(nullSafe(addendum.getAuthorName()))
            .authorCredentials(nullSafe(addendum.getAuthorCredentials()))
            .attestAccuracy(addendum.isAttestAccuracy())
            .attestNoAbbreviations(addendum.isAttestNoAbbreviations())
            .lateEntry(lateEntry)
            .build();
    }

    private EncounterLinkedArtifactDTO toEncounterLinkedArtifactDTO(EncounterNoteLink link) {
        if (link == null) {
            return null;
        }
        return EncounterLinkedArtifactDTO.builder()
            .artifactId(link.getArtifactId())
            .artifactType(link.getArtifactType())
            .artifactCode(nullSafe(link.getArtifactCode()))
            .artifactDisplay(nullSafe(link.getArtifactDisplay()))
            .artifactStatus(nullSafe(link.getArtifactStatus()))
            .linkedAt(link.getLinkedAt())
            .build();
    }

    public EncounterNoteHistoryResponseDTO toEncounterNoteHistoryResponseDTO(EncounterNoteHistory history) {
        if (history == null) {
            return null;
        }
        return EncounterNoteHistoryResponseDTO.builder()
            .id(history.getId())
            .noteId(history.getNoteId())
            .template(history.getTemplate())
            .changedAt(history.getChangedAt())
            .changedBy(nullSafe(history.getChangedBy()))
            .changeType(nullSafe(history.getChangeType()))
            .contentSnapshot(history.getContentSnapshot())
            .metadataSnapshot(history.getMetadataSnapshot())
            .build();
    }

    public Encounter mergeEncounter(
        EncounterRequestDTO dto,
        Encounter target,
        Patient patient,
        Staff staff,
        Hospital hospital,
        Appointment appointment,
        UserRoleHospitalAssignment assignment
    ) {
        Encounter e = (target != null) ? target : new Encounter();

        e.setPatient(patient);
        e.setStaff(staff);
        e.setHospital(hospital);
        e.setAppointment(appointment);
        e.setAssignment(assignment);

        // Use resolved departmentId if present
        Department resolved = null;
        if (hospital != null && hospital.getDepartments() != null) {
            UUID deptId = dto.getDepartmentId();
            if (deptId != null) {
                resolved = hospital.getDepartments().stream()
                    .filter(Objects::nonNull)
                    .filter(dep -> deptId.equals(dep.getId()))
                    .findFirst()
                    .orElse(null);
            } else if (dto.getDepartmentIdentifier() != null) {
                resolved = hospital.getDepartments().stream()
                    .filter(dep -> dep.getName().equalsIgnoreCase(dto.getDepartmentIdentifier()) ||
                                   (dep.getCode() != null && dep.getCode().equalsIgnoreCase(dto.getDepartmentIdentifier())))
                    .findFirst()
                    .orElse(null);
            }
        }
        e.setDepartment(resolved);

        if (dto.getEncounterType() != null) e.setEncounterType(EncounterType.valueOf(dto.getEncounterType().name()));
        if (dto.getEncounterDate() != null) e.setEncounterDate(dto.getEncounterDate());
        if (dto.getNotes() != null) e.setNotes(dto.getNotes());

        return e;
    }

    /* ---------------- helpers ---------------- */

    private String nullSafe(String v) { return (v == null || v.isBlank()) ? null : v; }

    public String joinName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
