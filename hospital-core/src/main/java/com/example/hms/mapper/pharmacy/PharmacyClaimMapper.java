package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PharmacyClaimMapper {

    public PharmacyClaimResponseDTO toResponseDTO(PharmacyClaim entity) {
        if (entity == null) {
            return null;
        }

        return PharmacyClaimResponseDTO.builder()
            .id(entity.getId())
            .dispenseId(entity.getDispense() != null ? entity.getDispense().getId() : null)
            .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .coverageReference(entity.getCoverageReference())
            .claimStatus(entity.getClaimStatus() != null ? entity.getClaimStatus().name() : null)
            .amount(entity.getAmount())
            .currency(entity.getCurrency())
            .submittedAt(entity.getSubmittedAt())
            .submittedBy(entity.getSubmittedByUser() != null ? entity.getSubmittedByUser().getId() : null)
            .rejectionReason(entity.getRejectionReason())
            .notes(entity.getNotes())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public PharmacyClaim toEntity(PharmacyClaimRequestDTO dto,
                                 Dispense dispense,
                                 Patient patient,
                                 Hospital hospital) {
        if (dto == null) {
            return null;
        }

        return PharmacyClaim.builder()
            .dispense(dispense)
            .patient(patient)
            .hospital(hospital)
            .coverageReference(dto.getCoverageReference())
            .claimStatus(dto.getClaimStatus() != null
                ? PharmacyClaimStatus.valueOf(dto.getClaimStatus()) : PharmacyClaimStatus.DRAFT)
            .amount(dto.getAmount())
            .currency(dto.getCurrency() != null ? dto.getCurrency() : "XOF")
            .rejectionReason(dto.getRejectionReason())
            .notes(dto.getNotes())
            .build();
    }
}
