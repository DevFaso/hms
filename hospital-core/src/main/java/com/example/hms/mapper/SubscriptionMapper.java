package com.example.hms.mapper;

import com.example.hms.model.platform.OrganizationSubscription;
import com.example.hms.model.platform.SubscriptionPlan;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionResponseDTO;
import com.example.hms.payload.dto.superadmin.SubscriptionPlanResponseDTO;
import org.springframework.stereotype.Component;

/**
 * MVP-6: Hand-written mappers per project convention (no MapStruct).
 */
@Component
public class SubscriptionMapper {

    public SubscriptionPlanResponseDTO toDto(SubscriptionPlan plan) {
        if (plan == null) {
            return null;
        }
        return SubscriptionPlanResponseDTO.builder()
            .id(plan.getId())
            .name(plan.getName())
            .tierCode(plan.getTierCode())
            .description(plan.getDescription())
            .monthlyPriceCents(plan.getMonthlyPriceCents())
            .currency(plan.getCurrency())
            .includedSeats(plan.getIncludedSeats())
            .featureKeys(plan.getFeatureKeys())
            .active(plan.isActive())
            .createdAt(plan.getCreatedAt())
            .updatedAt(plan.getUpdatedAt())
            .build();
    }

    public OrganizationSubscriptionResponseDTO toDto(OrganizationSubscription sub) {
        if (sub == null) {
            return null;
        }
        return OrganizationSubscriptionResponseDTO.builder()
            .id(sub.getId())
            .organizationId(sub.getOrganization() != null ? sub.getOrganization().getId() : null)
            .organizationName(sub.getOrganization() != null ? sub.getOrganization().getName() : null)
            .planId(sub.getPlan() != null ? sub.getPlan().getId() : null)
            .planName(sub.getPlan() != null ? sub.getPlan().getName() : null)
            .planTierCode(sub.getPlan() != null ? sub.getPlan().getTierCode() : null)
            .seatLimit(sub.getSeatLimit())
            .billingPeriod(sub.getBillingPeriod() != null ? sub.getBillingPeriod().name() : null)
            .status(sub.getStatus() != null ? sub.getStatus().name() : null)
            .startedAt(sub.getStartedAt())
            .endsAt(sub.getEndsAt())
            .build();
    }
}
