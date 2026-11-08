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
     * What a hospital administrator with no clinical role reads: only the urgent review of a disputed death addressed
     * to them. An administrator is otherwise off the clinical chart (E9 #67), so nothing else of this store reaches them.
     */
    org.springframework.data.domain.Page<InBasketItemDTO> getDisputeReviewItems(java.util.UUID userId, java.util.UUID hospitalId,
        com.example.hms.enums.InBasketItemStatus status, org.springframework.data.domain.Pageable pageable);

    com.example.hms.payload.dto.clinical.InBasketSummaryDTO getDisputeReviewSummary(java.util.UUID userId, java.util.UUID hospitalId);

    /** Answers as a missing item unless this is a dispute review addressed to this user. */
    void requireDisputeReviewItem(java.util.UUID itemId, java.util.UUID userId);

    /**
     * {@link #createItem} in a transaction of its own, for a caller that files
     * the item after its own transaction committed (QA D178: a released lab
     * result). A failure here never undoes the caller's clinical write.
     */
    InBasketItemDTO createItemInNewTransaction(CreateInBasketItemRequest request);

    /**
     * QA D931: removes every item filed for {@code referenceId} that nobody
     * has acknowledged, in the caller's transaction (a released lab result
     * that a correction has just superseded: its items would open on a 404).
     * ACKNOWLEDGED items stay, as the record of who reviewed the old value;
     * that someone merely opened it is in the read audit.
     * Returns how many were removed.
     */
    int removeOpenItemsForReference(java.util.UUID referenceId, String referenceType);
}
