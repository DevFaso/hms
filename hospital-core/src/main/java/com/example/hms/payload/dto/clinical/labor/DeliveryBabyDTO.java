package com.example.hms.payload.dto.clinical.labor;

import com.example.hms.enums.BabyOutcome;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * QA D1714 (user decision 2026-11-13): one baby of a delivery and its outcome. In a request, the babies are listed
 * in birth order (an omitted order is the position in the list). In a response, the stillborn baby's fetal-death
 * event is named; an outcome is null only for a later baby of a delivery recorded before V293 with a single
 * "not live" flag, which cannot say.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryBabyDTO {

    @Min(1) @Max(5)
    private Integer birthOrder;

    @NotNull
    private BabyOutcome outcome;

    /** Response only: the live fetal-death event of a stillborn baby. */
    private UUID fetalDeathEventId;
}
