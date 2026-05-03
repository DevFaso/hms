package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationSubscriptionResponseDTO {
    private UUID id;
    private UUID organizationId;
    private String organizationName;
    private UUID planId;
    private String planName;
    private String planTierCode;
    private int seatLimit;
    private String billingPeriod;
    private String status;
    private Instant startedAt;
    private Instant endsAt;
}
