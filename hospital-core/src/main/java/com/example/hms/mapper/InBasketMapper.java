package com.example.hms.mapper;

import com.example.hms.model.InBasketItem;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper for InBasketItem ↔ DTO conversions.
 */
@Component
public class InBasketMapper {

    public InBasketItemDTO toDto(InBasketItem item) {
        if (item == null) return null;

        return InBasketItemDTO.builder()
                .id(item.getId())
                .itemType(item.getItemType() != null ? item.getItemType().name() : null)
                .priority(item.getPriority() != null ? item.getPriority().name() : null)
                .status(item.getStatus() != null ? item.getStatus().name() : null)
                .title(item.getTitle())
                .body(item.getBody())
                .referenceId(item.getReferenceId())
                .referenceType(item.getReferenceType())
                .encounterId(item.getEncounter() != null ? item.getEncounter().getId() : null)
                .patientId(item.getPatient() != null ? item.getPatient().getId() : null)
                .patientName(item.getPatientName())
                .orderingProviderName(item.getOrderingProviderName())
                .createdAt(item.getCreatedAt())
                .readAt(item.getReadAt())
                .acknowledgedAt(item.getAcknowledgedAt())
                .build();
    }
}
