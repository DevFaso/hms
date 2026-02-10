package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PatientProblemResponseDTO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PatientProblemMapper {

    public PatientProblemResponseDTO toResponseDto(PatientProblem problem) {
        if (problem == null) {
            return null;
        }

        Hospital hospital = problem.getHospital();
        Staff staff = problem.getRecordedBy();

        String recordedBy = null;
        if (staff != null) {
            recordedBy = staff.getName();
            if ((recordedBy == null || recordedBy.isBlank()) && staff.getUser() != null) {
                String first = staff.getUser().getFirstName();
                String last = staff.getUser().getLastName();
                recordedBy = buildName(first, last);
            }
        }

        return PatientProblemResponseDTO.builder()
            .id(problem.getId())
            .patientId(problem.getPatient() != null ? problem.getPatient().getId() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .problemCode(problem.getProblemCode())
            .problemDisplay(problem.getProblemDisplay())
            .icdVersion(problem.getIcdVersion())
            .status(problem.getStatus() != null ? problem.getStatus().name() : null)
            .severity(problem.getSeverity() != null ? problem.getSeverity().name() : null)
            .onsetDate(problem.getOnsetDate())
            .resolvedDate(problem.getResolvedDate())
            .lastReviewedAt(problem.getLastReviewedAt())
            .recordedBy(recordedBy)
            .sourceSystem(problem.getSourceSystem())
        .notes(problem.getNotes())
        .supportingEvidence(problem.getSupportingEvidence())
        .statusChangeReason(problem.getStatusChangeReason())
        .chronic(problem.isChronic())
        .diagnosisCodes(problem.getDiagnosisCodes() == null ? List.of() : List.copyOf(problem.getDiagnosisCodes()))
            .build();
    }

    private String buildName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String combined = (f + " " + l).trim();
        return combined.isEmpty() ? null : combined;
    }
}
