package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationSubscriptionRequestDTO {

    @NotNull
    private UUID planId;

    @PositiveOrZero
    private int seatLimit;

    @Size(max = 20)
    private String billingPeriod;
}
