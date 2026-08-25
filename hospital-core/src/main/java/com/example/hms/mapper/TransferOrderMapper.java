package com.example.hms.mapper;

import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.WardType;
import com.example.hms.model.Bed;
import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.TransferOrder;
import com.example.hms.model.Ward;
import com.example.hms.payload.dto.transfer.TransferOrderResponseDTO;
import com.example.hms.service.BedAssignmentService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity to DTO for transfer orders (Tier 2 item 30).
 *
 * <p>{@code destinationIsolationMismatch} is derived on READ, like the WHO
 * maternal flag in V135 and the NEWS2 banding in V130. Precautions change
 * after an order is raised — a swab comes back positive while the patient is
 * still waiting for a porter — and a stored verdict would be a stale one at
 * exactly the moment it mattered.
 */
@Component
public class TransferOrderMapper {

    public TransferOrderResponseDTO toDto(TransferOrder order,
                                          List<IsolationPrecaution> activePrecautions) {
        if (order == null) {
            return null;
        }
        Patient patient = order.getPatient();
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;

        List<IsolationPrecautionType> types = activePrecautions == null ? List.of()
            : activePrecautions.stream()
                .map(IsolationPrecaution::getPrecautionType)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        boolean needsIsolationWard = activePrecautions != null && activePrecautions.stream()
            .anyMatch(IsolationPrecaution::requiresIsolationWard);

        return TransferOrderResponseDTO.builder()
            .id(order.getId())
            .admissionId(order.getAdmission() != null ? order.getAdmission().getId() : null)
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            // MRN is per-hospital, so it resolves against the transferring facility.
            .mrn(patient != null && hospitalId != null ? patient.getMrnForHospital(hospitalId) : null)
            .fromBedId(order.getFromBed() != null ? order.getFromBed().getId() : null)
            .fromBedLabel(bedLabel(order.getFromBed()))
            .fromWardName(wardName(order.getFromWard()))
            .toBedId(order.getToBed() != null ? order.getToBed().getId() : null)
            .toBedLabel(bedLabel(order.getToBed()))
            .toWardName(wardName(order.getToWard()))
            .transferType(order.getTransferType())
            .status(order.getStatus())
            .reason(order.getReason())
            .notes(order.getNotes())
            .requestedByName(staffName(order.getRequestedBy()))
            .requestedAt(order.getRequestedAt())
            .completedByName(staffName(order.getCompletedBy()))
            .completedAt(order.getCompletedAt())
            .cancelledByName(staffName(order.getCancelledBy()))
            .cancelledAt(order.getCancelledAt())
            .cancellationReason(order.getCancellationReason())
            .isolationOverride(order.isIsolationOverride())
            .isolationOverrideReason(order.getIsolationOverrideReason())
            .isolationPrecautions(types)
            .destinationIsolationMismatch(
                needsIsolationWard && !isIsolationCapable(order.getToWard()))
            .build();
    }

    /** Only an isolation ward can contain an airborne case. */
    private boolean isIsolationCapable(Ward ward) {
        return ward != null && ward.getWardType() == WardType.ISOLATION;
    }

    private String bedLabel(Bed bed) {
        return bed == null ? null : BedAssignmentService.bedLabel(bed);
    }

    private String wardName(Ward ward) {
        return ward == null ? null : ward.getName();
    }

    private String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String full = staff.getFullName();
        return full != null && !full.isBlank() ? full : staff.getName();
    }
}
