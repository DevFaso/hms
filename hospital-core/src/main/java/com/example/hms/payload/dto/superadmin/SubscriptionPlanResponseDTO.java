package com.example.hms.payload.dto.superadmin;

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
public class SubscriptionPlanResponseDTO {
    private UUID id;
    private String name;
    private String tierCode;
    private String description;
    private long monthlyPriceCents;
    private String currency;
    private int includedSeats;
    private String featureKeys;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
