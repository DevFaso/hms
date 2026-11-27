package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
/** r63: a single value, or (for a panel test) its component values. */
@OneOf(fields = {"resultValue", "components"}, message = "{lab.result.value.required}")
public class LabResultRequestDTO {

    private UUID id;

    @NotNull
    private UUID labOrderId;

    @NotNull
    private UUID assignmentId;

    @NotNull
    private UUID patientId;

    /**
     * QA D1387 (round 32): within its column, refused by name (not a generic constraint 400).
     * r63: required for a single-value test (LabResultServiceImpl names it when missing); for a
     * panel the service composes it from {@link #components}.
     */
    @jakarta.validation.constraints.Size(max = 2048)
    private String resultValue;

    /** QA D1387 (round 32): within its column, refused by name (not a generic constraint 400). */
    @jakarta.validation.constraints.Size(max = 50)
    private String resultUnit;

    /**
     * The analyte this result is for (OBX-3 on an HL7 ORU).
     *
     * <p><b>Ingest only.</b> The service discards it on the interactive path,
     * whatever a client sends: a hand-entered result is nobody's preliminary,
     * and the superseded-preliminary rule that lets an order complete keys on
     * this field — a client that could set it could mark its own result
     * superseded and let the order complete carrying an unreleased, unreviewed
     * value. Bounded to the column it lands in.
     */
    @Size(max = 255, message = "testCode must not exceed 255 characters")
    private String testCode;

    /**
     * The message this result arrived on, for replay detection.
     *
     * <p><b>Ingest only</b>, like {@link #testCode}: the HL7 inbound adapter
     * fills it from MSH-3, MSH-4 and MSH-10, and the service discards it on
     * the interactive path. A retransmitted ORU reuses all three, which is
     * what lets the adapter recognise it as the message it already recorded.
     */
    @Size(max = 255, message = "sourceSendingApplication must not exceed 255 characters")
    private String sourceSendingApplication;

    @Size(max = 255, message = "sourceSendingFacility must not exceed 255 characters")
    private String sourceSendingFacility;

    @Size(max = 255, message = "sourceMessageControlId must not exceed 255 characters")
    private String sourceMessageControlId;

    @NotNull
    private LocalDateTime resultDate;

    /** QA D1387 (round 32): within its column, refused by name (not a generic constraint 400). */
    @jakarta.validation.constraints.Size(max = 2048)
    private String notes;

    /**
     * D81: the RELEASED result on the same order that this new result
     * corrects. Optional; ignored on the ingest path.
     */
    private UUID supersedesResultId;

    /**
     * QA D4143: why the released result is corrected. Required with {@link #supersedesResultId}; ignored on a
     * result that corrects nothing and on the ingest path.
     */
    @jakarta.validation.constraints.Size(max = 500)
    private String correctionReason;

    /** r63 (NFS panels): one value per component of a panel test; ignored on the ingest path. */
    @jakarta.validation.Valid
    @jakarta.validation.constraints.Size(max = 60)
    private java.util.List<LabResultComponentValueDTO> components;
}

