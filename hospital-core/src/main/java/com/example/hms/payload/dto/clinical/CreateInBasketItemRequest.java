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
    /**
     * QA D1053: the message keys and arguments of the title and body, so the
     * item is rendered in the reader's language. {@link #title} / {@link #body}
     * stay the staff-language text, stored for old clients.
     */
    private String titleKey;
    private Object[] titleArgs;
    private String bodyKey;
    private Object[] bodyArgs;
    private UUID referenceId;
    private String referenceType;
    private UUID encounterId;
    private UUID patientId;
    private String patientName;
    private String orderingProviderName;
}
