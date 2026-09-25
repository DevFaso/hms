package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionResponseDTO;
import com.example.hms.service.pharmacy.PartnerNoShowReason;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class PrescriptionRoutingMapper {

    public record RoutingContext(
            Prescription prescription,
            Pharmacy targetPharmacy,
            User decidedByUser,
            Patient patient,
            java.math.BigDecimal remainingQuantity
    ) {}

    public RoutingDecisionResponseDTO toResponseDTO(PrescriptionRoutingDecision entity) {
        if (entity == null) {
            return null;
        }

        return RoutingDecisionResponseDTO.builder()
                .id(entity.getId())
                .prescriptionId(entity.getPrescription() != null ? entity.getPrescription().getId() : null)
                .routingType(entity.getRoutingType() != null ? entity.getRoutingType().name() : null)
                .targetPharmacyId(entity.getTargetPharmacy() != null ? entity.getTargetPharmacy().getId() : null)
                .targetPharmacyName(entity.getTargetPharmacy() != null ? entity.getTargetPharmacy().getName() : null)
                .decidedByUserId(entity.getDecidedByUser() != null ? entity.getDecidedByUser().getId() : null)
                .patientId(entity.getDecidedForPatient() != null ? entity.getDecidedForPatient().getId() : null)
                // The no-show fact travels as a flag plus the pharmacist's own
                // words, never as a composed sentence: a sentence stored in
                // English reaches a French or Spanish prescriber in English.
                // Rows written before the marker existed carry the old literal
                // and decode the same way.
                .reason(PartnerNoShowReason.withoutNoShow(entity.getReason()))
                .partnerNoShow(PartnerNoShowReason.isNoShow(entity.getReason()))
                .noShowReason(PartnerNoShowReason.freeText(entity.getReason()))
                .estimatedRestockDate(entity.getEstimatedRestockDate())
                .remainingQuantity(entity.getRemainingQuantity())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .decidedAt(entity.getDecidedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PrescriptionRoutingDecision toEntity(RoutingDecisionRequestDTO dto, RoutingContext ctx) {
        if (dto == null) {
            return null;
        }

        return PrescriptionRoutingDecision.builder()
                .prescription(ctx.prescription())
                .routingType(dto.getRoutingType())
                .targetPharmacy(ctx.targetPharmacy())
                .decidedByUser(ctx.decidedByUser())
                .decidedForPatient(ctx.patient())
                .reason(dto.getReason())
                .estimatedRestockDate(dto.getEstimatedRestockDate())
                .remainingQuantity(ctx.remainingQuantity())
                .status(RoutingDecisionStatus.PENDING)
                .decidedAt(LocalDateTime.now())
                .build();
    }
}
