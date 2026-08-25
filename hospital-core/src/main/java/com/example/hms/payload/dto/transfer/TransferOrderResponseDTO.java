package com.example.hms.payload.dto.transfer;

import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.TransferOrderStatus;
import com.example.hms.enums.TransferType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferOrderResponseDTO {

    private UUID id;
    private UUID admissionId;
    private UUID patientId;
    private String patientName;
    private String mrn;

    /** Where the patient was when the order was raised — a snapshot, not a join. */
    private UUID fromBedId;
    private String fromBedLabel;
    private String fromWardName;

    private UUID toBedId;
    private String toBedLabel;
    private String toWardName;

    private TransferType transferType;
    private TransferOrderStatus status;
    private String reason;
    private String notes;

    private String requestedByName;
    private LocalDateTime requestedAt;
    private String completedByName;
    private LocalDateTime completedAt;
    private String cancelledByName;
    private LocalDateTime cancelledAt;
    private String cancellationReason;

    private boolean isolationOverride;
    private String isolationOverrideReason;

    /**
     * The precautions in force for this patient right now, so the porter
     * reading the worklist sees them without opening the chart.
     */
    private List<IsolationPrecautionType> isolationPrecautions;

    /** Derived on read: the destination cannot contain an active airborne case. */
    private boolean destinationIsolationMismatch;
}
