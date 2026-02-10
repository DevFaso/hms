package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.PatientSurgicalHistory;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PatientSurgicalHistoryResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PatientSurgicalHistoryMapper {

    public PatientSurgicalHistoryResponseDTO toResponseDto(PatientSurgicalHistory history) {
        if (history == null) {
            return null;
        }

        Hospital hospital = history.getHospital();
        Staff staff = history.getPerformedBy();

        String performer = null;
        if (staff != null) {
            performer = staff.getName();
            if ((performer == null || performer.isBlank()) && staff.getUser() != null) {
                String first = staff.getUser().getFirstName();
                String last = staff.getUser().getLastName();
                performer = buildName(first, last);
            }
        }

        return PatientSurgicalHistoryResponseDTO.builder()
            .id(history.getId())
            .patientId(history.getPatient() != null ? history.getPatient().getId() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .procedureCode(history.getProcedureCode())
            .procedureDisplay(history.getProcedureDisplay())
            .procedureDate(history.getProcedureDate())
            .outcome(history.getOutcome() != null ? history.getOutcome().name() : null)
            .performedBy(performer)
            .location(history.getLocation())
            .sourceSystem(history.getSourceSystem())
            .lastUpdatedAt(history.getLastUpdatedAt())
            .notes(history.getNotes())
            .build();
    }

    private String buildName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String combined = (f + " " + l).trim();
        return combined.isEmpty() ? null : combined;
    }
}
