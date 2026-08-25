package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.DispenseCheck;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DispenseMapper {

    public record DispenseContext(
            Prescription prescription,
            Patient patient,
            Pharmacy pharmacy,
            StockLot stockLot,
            User dispensedByUser,
            User verifiedByUser,
            MedicationCatalogItem medicationCatalogItem
    ) {}

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
            .verificationStatus(entity.getVerificationStatus() != null
                ? entity.getVerificationStatus().name() : null)
            .scanVerifiedAt(entity.getScanVerifiedAt())
            .verificationOverrides(parseOverrides(entity.getVerificationOverrides()))
            .verificationOverrideReason(entity.getVerificationOverrideReason())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * The column holds a JSON array of {@link DispenseCheck} names. Parsed by
     * matching against the enum rather than by reading the JSON, so a
     * malformed or hand-edited value yields the checks it does name and
     * silently drops anything that is not a real check — a display field
     * must not be able to fail a dispense read.
     */
    private List<String> parseOverrides(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return Arrays.stream(DispenseCheck.values())
            .map(Enum::name)
            .filter(name -> json.contains("\"" + name + "\""))
            .toList();
    }

    public Dispense toEntity(DispenseRequestDTO dto, DispenseContext ctx) {
        if (dto == null) {
            return null;
        }

        return Dispense.builder()
            .prescription(ctx.prescription())
            .patient(ctx.patient())
            .pharmacy(ctx.pharmacy())
            .stockLot(ctx.stockLot())
            .dispensedByUser(ctx.dispensedByUser())
            .verifiedByUser(ctx.verifiedByUser())
            .medicationCatalogItem(ctx.medicationCatalogItem())
            .medicationName(dto.getMedicationName())
            .quantityRequested(dto.getQuantityRequested())
            .quantityDispensed(dto.getQuantityDispensed())
            .unit(dto.getUnit())
            .substitution(Boolean.TRUE.equals(dto.getSubstitution()))
            .substitutionReason(dto.getSubstitutionReason())
            .status(dto.getStatus() != null ? dto.getStatus() : DispenseStatus.COMPLETED)
            .notes(dto.getNotes())
            // Roadmap row 4 / T-68: blank → null so the partial UNIQUE
            // index (uq_disp_idempotency_key) ignores opted-out callers.
            .idempotencyKey(blankToNull(dto.getIdempotencyKey()))
            .build();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
