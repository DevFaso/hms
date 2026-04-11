package com.example.hms.payload.dto.clinical;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Internal request object for creating an In-Basket item (not exposed via REST).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInBasketItemRequest {

    private UUID recipientUserId;
    private UUID hospitalId;
    private InBasketItemType itemType;
    @Builder.Default
    private InBasketPriority priority = InBasketPriority.NORMAL;
    private String title;
    private String body;
    private UUID referenceId;
    private String referenceType;
    private UUID encounterId;
    private UUID patientId;
    private String patientName;
    private String orderingProviderName;
}
