package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class DispenseMapper {

    public DispenseResponseDTO toResponseDTO(Dispense entity) {
        if (entity == null) {
            return null;
        }

        return DispenseResponseDTO.builder()
            .id(entity.getId())
            .prescriptionId(entity.getPrescription() != null ? entity.getPrescription().getId() : null)
            .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
            .pharmacyId(entity.getPharmacy() != null ? entity.getPharmacy().getId() : null)
            .stockLotId(entity.getStockLot() != null ? entity.getStockLot().getId() : null)
            .dispensedBy(entity.getDispensedByUser() != null ? entity.getDispensedByUser().getId() : null)
            .verifiedBy(entity.getVerifiedByUser() != null ? entity.getVerifiedByUser().getId() : null)
            .medicationCatalogItemId(entity.getMedicationCatalogItem() != null
                ? entity.getMedicationCatalogItem().getId() : null)
            .medicationName(entity.getMedicationName())
            .quantityRequested(entity.getQuantityRequested())
            .quantityDispensed(entity.getQuantityDispensed())
            .unit(entity.getUnit())
            .substitution(entity.isSubstitution())
            .substitutionReason(entity.getSubstitutionReason())
            .status(entity.getStatus() != null ? entity.getStatus().name() : null)
            .notes(entity.getNotes())
            .dispensedAt(entity.getDispensedAt())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public Dispense toEntity(DispenseRequestDTO dto,
                             Prescription prescription,
                             Patient patient,
                             Pharmacy pharmacy,
                             StockLot stockLot,
                             User dispensedByUser,
                             User verifiedByUser,
                             MedicationCatalogItem medicationCatalogItem) {
        if (dto == null) {
            return null;
        }

        return Dispense.builder()
            .prescription(prescription)
            .patient(patient)
            .pharmacy(pharmacy)
            .stockLot(stockLot)
            .dispensedByUser(dispensedByUser)
            .verifiedByUser(verifiedByUser)
            .medicationCatalogItem(medicationCatalogItem)
            .medicationName(dto.getMedicationName())
            .quantityRequested(dto.getQuantityRequested())
            .quantityDispensed(dto.getQuantityDispensed())
            .unit(dto.getUnit())
            .substitution(Boolean.TRUE.equals(dto.getSubstitution()))
            .substitutionReason(dto.getSubstitutionReason())
            .status(dto.getStatus() != null ? dto.getStatus() : DispenseStatus.COMPLETED)
            .notes(dto.getNotes())
            .build();
    }
}
