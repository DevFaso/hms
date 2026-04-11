package com.example.hms.service;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.payload.dto.clinical.InBasketSummaryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service for the provider In-Basket notification system.
 */
public interface InBasketService {

    /**
     * Retrieve paginated In-Basket items for the current user.
     */
    Page<InBasketItemDTO> getItems(UUID userId, UUID hospitalId,
                                    InBasketItemType type, InBasketItemStatus status,
                                    Pageable pageable);

    /**
     * Get unread count summary grouped by item type.
     */
    InBasketSummaryDTO getSummary(UUID userId, UUID hospitalId);

    /**
     * Mark an item as read (viewed).
     */
    InBasketItemDTO markAsRead(UUID itemId, UUID userId);

    /**
     * Formally acknowledge an item — records who and when.
     */
    InBasketItemDTO acknowledge(UUID itemId, UUID userId);

    /**
     * Create an in-basket item — used by event listeners when results are filed.
     */
    InBasketItemDTO createItem(CreateInBasketItemRequest request);
}
