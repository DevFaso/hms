package com.example.hms.mapper;

import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;
import org.springframework.stereotype.Component;

/**
 * Entity to DTO for isolation precautions (Tier 2 item 32).
 *
 * <p>{@code active} and {@code requiresIsolationWard} are derived on READ
 * rather than stored — the same stance V130 took for NEWS2 and V135 for the
 * WHO maternal-death flag. A stored classification goes stale the moment the
 * precaution is discontinued.
 */
@Component
public class IsolationMapper {

    public IsolationPrecautionResponseDTO toDto(IsolationPrecaution precaution) {
        if (precaution == null) {
            return null;
        }
        Patient patient = precaution.getPatient();

        return IsolationPrecautionResponseDTO.builder()
            .id(precaution.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            .admissionId(precaution.getAdmission() != null ? precaution.getAdmission().getId() : null)
            .precautionType(precaution.getPrecautionType())
            .reason(precaution.getReason())
            .suspectedOrganism(precaution.getSuspectedOrganism())
            .startedAt(precaution.getStartedAt())
            .orderedByName(staffName(precaution.getOrderedBy()))
            .endedAt(precaution.getEndedAt())
            .discontinuedByName(staffName(precaution.getDiscontinuedBy()))
            .discontinuationReason(precaution.getDiscontinuationReason())
            .active(precaution.isActive())
            .requiresIsolationWard(precaution.requiresIsolationWard())
            .notes(precaution.getNotes())
            .build();
    }

    private String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String full = staff.getFullName();
        return full != null && !full.isBlank() ? full : staff.getName();
    }
}
