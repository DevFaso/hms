package com.example.hms.mapper;

import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            if (request != null && request.getFollowUpAppointment() != null
                    && request.getFollowUpAppointment().getPreferredDate() != null) {
                builder.followUpAppointmentDate(request.getFollowUpAppointment().getPreferredDate());
            }
        }

        return builder.build();
    }

    /** Serialize a list of diagnoses to JSON text for persistence. */
    public String serializeDiagnoses(List<String> diagnoses) {
        if (diagnoses == null || diagnoses.isEmpty()) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(diagnoses);
        } catch (JsonProcessingException e) {
            return diagnoses.toString();
        }
    }

    /** Parse JSON text back to a list of diagnoses. */
    public List<String> parseDiagnoses(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
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
