package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlanRequestDTO {

    @NotBlank
    @Size(max = 120)
    private String name;

    @NotBlank
    @Size(max = 60)
    private String tierCode;

    @Size(max = 1000)
    private String description;

    @PositiveOrZero
    private long monthlyPriceCents;

    @Size(max = 10)
    private String currency;

    @PositiveOrZero
    private int includedSeats;

    /** Comma-separated list of feature keys this plan unlocks. */
    private String featureKeys;

    private Boolean active;
}
