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

    /**
     * {@link #createItem} in a transaction of its own, for a caller that files
     * the item after its own transaction committed (QA D178: a released lab
     * result). A failure here never undoes the caller's clinical write.
     */
    InBasketItemDTO createItemInNewTransaction(CreateInBasketItemRequest request);

    /**
     * QA D931: removes every item filed for {@code referenceId} that nobody has
     * acknowledged yet, in the caller's transaction (a released lab result that
     * a correction has just superseded: its items would open on a 404).
     * Acknowledged items stay, as the record of who reviewed the old value.
     * Returns how many were removed.
     */
    int removeOpenItemsForReference(java.util.UUID referenceId, String referenceType);
}
