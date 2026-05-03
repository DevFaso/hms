package com.example.hms.payload.dto.superadmin;

import com.example.hms.model.platform.OrganizationSubscription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

    /**
     * PR #228 review — typed as the enum so unknown values are rejected
     * with HTTP 400 by Spring's enum binder instead of being silently
     * coerced to MONTHLY by the service. Null is still allowed and
     * defaulted to MONTHLY at the service boundary.
     */
    private OrganizationSubscription.BillingPeriod billingPeriod;
}
