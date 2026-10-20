package com.example.hms.mapper;

import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Maps check-out request / encounter data → AfterVisitSummaryDTO (MVP 6).
 */
@Component
public class CheckOutMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Builds an After-Visit Summary from the encounter, the original request,
     * and an optional follow-up appointment ID.
     */
    public AfterVisitSummaryDTO toAfterVisitSummary(Encounter encounter,
                                                     CheckOutRequestDTO request,
                                                     UUID followUpAppointmentId) {
        AfterVisitSummaryDTO.AfterVisitSummaryDTOBuilder builder = AfterVisitSummaryDTO.builder()
            .encounterId(encounter.getId())
            .visitDate(encounter.getEncounterDate())
            .checkoutTimestamp(encounter.getCheckoutTimestamp())
            .encounterStatus(encounter.getStatus() != null ? encounter.getStatus().name() : null)
            .chiefComplaint(encounter.getChiefComplaint())
            .patientId(encounter.getPatient() != null ? encounter.getPatient().getId() : null)
            .patientName(derivePatientName(encounter))
            .providerName(deriveProviderName(encounter))
            .departmentName(encounter.getDepartment() != null ? encounter.getDepartment().getName() : null)
            .hospitalName(encounter.getHospital() != null ? encounter.getHospital().getName() : null);

        // Appointment info
        Appointment appt = encounter.getAppointment();
        if (appt != null) {
            builder.appointmentId(appt.getId())
                   .appointmentStatus(appt.getStatus() != null ? appt.getStatus().name() : null);
        }

        // From the request
        if (request != null) {
            builder.dischargeDiagnoses(request.getDischargeDiagnoses() != null
                    ? request.getDischargeDiagnoses()
                    : Collections.emptyList())
                   .prescriptionSummary(request.getPrescriptionSummary())
                   .referralSummary(request.getReferralSummary())
                   .followUpInstructions(request.getFollowUpInstructions())
                   .patientEducationMaterials(request.getPatientEducationMaterials());
        } else {
            builder.dischargeDiagnoses(parseDiagnoses(encounter.getDischargeDiagnoses()))
                   .followUpInstructions(encounter.getFollowUpInstructions());
        }

        // Follow-up appointment
        if (followUpAppointmentId != null) {
            builder.followUpAppointmentId(followUpAppointmentId);
        }
        // QA D321: the requested follow-up is shown even when no appointment
        // was booked from it (checkout files it as a recall); it used to need an
        // appointment id that checkout never passes, so it vanished from the AVS.
        if (request != null && request.getFollowUpAppointment() != null
                && request.getFollowUpAppointment().getPreferredDate() != null
                && !request.getFollowUpAppointment().getPreferredDate().isBlank()) {
            builder.followUpAppointmentDate(request.getFollowUpAppointment().getPreferredDate());
        }

        AfterVisitSummaryDTO summary = builder.build();
        fillFromSignedNote(summary, encounter.getEncounterNote());
        return summary;
    }

    /**
     * The summary used to read only the checkout form and the two encounter
     * columns it writes, so a visit documented in a signed note but checked out
     * with an empty form produced a summary with nothing in it (QA D42). Each
     * blank section now falls back to the signed note: chief complaint, the
     * assessment as the diagnosis, and the patient instructions (or the plan)
     * as follow-up. Anything the clinician typed at checkout still wins, and an
     * unsigned draft is never shown to the patient.
     */
    private static void fillFromSignedNote(AfterVisitSummaryDTO summary, EncounterNote note) {
        if (note == null || note.getSignedAt() == null) {
            return;
        }
        if (isBlank(summary.getChiefComplaint())) {
            summary.setChiefComplaint(trimToNull(note.getChiefComplaint()));
        }
        if (summary.getDischargeDiagnoses() == null || summary.getDischargeDiagnoses().isEmpty()) {
            String assessment = trimToNull(note.getAssessment());
            summary.setDischargeDiagnoses(assessment != null ? List.of(assessment) : Collections.emptyList());
        }
        if (isBlank(summary.getFollowUpInstructions())) {
            String instructions = trimToNull(note.getPatientInstructions());
            summary.setFollowUpInstructions(instructions != null ? instructions : trimToNull(note.getPlan()));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** Serialize a list of diagnoses to JSON text for persistence. */
    public String serializeDiagnoses(List<String> diagnoses) {
        if (diagnoses == null || diagnoses.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(diagnoses);
        } catch (JacksonException e) {
            return diagnoses.toString();
        }
    }

    /** Parse JSON text back to a list of diagnoses. */
    public List<String> parseDiagnoses(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JacksonException e) {
            return List.of(json);
        }
    }

    /* ---------- Private helpers ---------- */

    private String derivePatientName(Encounter enc) {
        if (enc.getPatient() == null) return null;
        String first = enc.getPatient().getFirstName();
        String last = enc.getPatient().getLastName();
        if (first == null && last == null) return null;
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

    private String deriveProviderName(Encounter enc) {
        if (enc.getStaff() == null) return null;
        if (enc.getStaff().getUser() != null) {
            String first = enc.getStaff().getUser().getFirstName();
            String last = enc.getStaff().getUser().getLastName();
            if (first != null || last != null) {
                return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            }
        }
        return enc.getStaff().getName();
    }
}
