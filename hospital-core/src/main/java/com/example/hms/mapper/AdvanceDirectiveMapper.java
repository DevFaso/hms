package com.example.hms.mapper;

import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class AdvanceDirectiveMapper {

    public AdvanceDirectiveResponseDTO toResponseDto(AdvanceDirective directive) {
        if (directive == null) {
            return null;
        }

        Hospital hospital = directive.getHospital();

        return AdvanceDirectiveResponseDTO.builder()
            .id(directive.getId())
            .patientId(directive.getPatient() != null ? directive.getPatient().getId() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .directiveType(directive.getDirectiveType() != null ? directive.getDirectiveType().name() : null)
            .status(directive.getStatus() != null ? directive.getStatus().name() : null)
            .description(directive.getDescription())
            .effectiveDate(directive.getEffectiveDate())
            .expirationDate(directive.getExpirationDate())
            .witnessName(directive.getWitnessName())
            .physicianName(directive.getPhysicianName())
            .documentLocation(directive.getDocumentLocation())
            .sourceSystem(directive.getSourceSystem())
            .lastReviewedAt(directive.getLastReviewedAt())
            .build();
    }
}
