package com.example.hms.payload.dto.clinical.labor;

import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InfantSex;
import com.example.hms.enums.PerinealTear;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** Payload for filing the delivery record of a labor episode. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@com.example.hms.validation.RecentDeliveryOverrideGiven(message = "{labor.delivery.recentDelivery}")
public class DeliveryRecordRequestDTO {

    private UUID hospitalId;
    private UUID deliveredByStaffId;

    @com.example.hms.validation.NotInClinicalFuture

    private LocalDateTime birthDateTime;

    @NotNull(message = "{delivery.validation.modeRequired}")
    private DeliveryMode deliveryMode;

    /** A single-baby shortcut kept for older clients; {@link #babies} is the per-baby form (QA D1714). */
    private Boolean liveBirth;

    /**
     * QA D1714 (user decision 2026-11-13): the outcome of each baby, LIVE or STILLBORN, in birth order, so mixed
     * twins can be recorded. Live babies are registered as patients; each stillborn one creates a fetal-death
     * event. When given, {@link #numberOfInfants} and {@link #liveBirth} (if sent) must agree with it.
     */
    @jakarta.validation.Valid
    @jakarta.validation.constraints.Size(max = 5, message = "{delivery.validation.babiesTooMany}")
    private java.util.List<DeliveryBabyDTO> babies;

    @Min(value = 1, message = "{delivery.validation.numberOfInfants}") @Max(value = 5, message = "{delivery.validation.numberOfInfants}")
    private Integer numberOfInfants;

    private InfantSex infantSex;

    @Min(value = 200, message = "{delivery.validation.birthWeight}") @Max(value = 9000, message = "{delivery.validation.birthWeight}")
    private Integer birthWeightGrams;

    @Min(value = 20, message = "{delivery.validation.gestationalAge}") @Max(value = 45, message = "{delivery.validation.gestationalAge}")
    private Integer gestationalAgeWeeksAtBirth;

    @Min(value = 0, message = "{delivery.validation.apgar}") @Max(value = 10, message = "{delivery.validation.apgar}")
    private Integer apgarOneMinute;
    @Min(value = 0, message = "{delivery.validation.apgar}") @Max(value = 10, message = "{delivery.validation.apgar}")
    private Integer apgarFiveMinute;

    @com.example.hms.validation.NotInClinicalFuture

    private LocalDateTime placentaDeliveredAt;
    private Boolean placentaComplete;
    private Boolean uterotonicGiven;

    @Min(value = 0, message = "{delivery.validation.bloodLoss}") @Max(value = 5000, message = "{delivery.validation.bloodLoss}")
    private Integer estimatedBloodLossMl;

    private PerinealTear perinealTear;

    private String notes;

    /**
     * QA D214 (#896 review): required only when another birth was recorded
     * for this mother within 20 weeks of this one; kept on the record notes.
     */
    @jakarta.validation.constraints.Size(max = 500, message = "{validation.reason.size}")
    private String recentDeliveryOverrideReason;

    /**
     * QA r46 (D1712 follow-up, provisional rule pending clinical sign-off): on an episode that continues a labour
     * from another hospital, the baby was born before she arrived (in transit). Only then may the birth time be
     * earlier than the episode's start; {@link #bornBeforeArrivalReason} is then required.
     */
    private Boolean bornBeforeArrival;

    @jakarta.validation.constraints.Size(max = 500, message = "{validation.reason.size}")
    private String bornBeforeArrivalReason;
}
