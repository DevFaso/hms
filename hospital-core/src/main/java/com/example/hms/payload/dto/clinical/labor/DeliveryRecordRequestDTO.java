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
public class DeliveryRecordRequestDTO {

    private UUID hospitalId;
    private UUID deliveredByStaffId;

    private LocalDateTime birthDateTime;

    @NotNull
    private DeliveryMode deliveryMode;

    private Boolean liveBirth;

    @Min(1) @Max(5)
    private Integer numberOfInfants;

    private InfantSex infantSex;

    @Min(200) @Max(9000)
    private Integer birthWeightGrams;

    @Min(20) @Max(45)
    private Integer gestationalAgeWeeksAtBirth;

    @Min(0) @Max(10)
    private Integer apgarOneMinute;
    @Min(0) @Max(10)
    private Integer apgarFiveMinute;

    private LocalDateTime placentaDeliveredAt;
    private Boolean placentaComplete;
    private Boolean uterotonicGiven;

    @Min(0) @Max(5000)
    private Integer estimatedBloodLossMl;

    private PerinealTear perinealTear;

    private String notes;
}
