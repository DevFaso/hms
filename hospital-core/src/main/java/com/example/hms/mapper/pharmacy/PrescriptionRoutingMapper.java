package com.example.hms.mapper.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
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

        // A no-show is a CANCELLED partner decision, so the status is half the
        // question and the marker is the other half. Reading the text alone
        // was enough for a row written from now on — defuseAuthoredReason sees
        // to that — but says nothing about the rows already in the table: one
        // whose authored reason happens to begin "Partner no-show: …" would
        // have rendered "The partner never delivered" on a partner decision
        // still PENDING, and lost the real reason out of the field while doing
        // it. Both must agree.
        boolean noShow = entity.getStatus() == RoutingDecisionStatus.CANCELLED
                && entity.getRoutingType() == RoutingType.PARTNER
                && PartnerNoShowReason.isNoShow(entity.getReason());

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
                // and decode the same way. On any row the status does not
                // corroborate, the words are handed over with the marker
                // stripped: it is an implementation detail either way, and a
                // prescriber should never read one.
                .reason(noShow
                        ? PartnerNoShowReason.withoutNoShow(entity.getReason())
                        : PartnerNoShowReason.forDisplay(entity.getReason()))
                .partnerNoShow(noShow)
                .noShowReason(noShow ? PartnerNoShowReason.freeText(entity.getReason()) : null)
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
