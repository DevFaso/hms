package com.example.hms.repository;

import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.model.InBasketItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InBasketItemRepository extends JpaRepository<InBasketItem, UUID> {

    /**
     * Paginated list for a recipient, filtered by optional type, status,
     * and hospital. The hospital filter is optional: when {@code hospitalId}
     * is {@code null} the controller has authorised a cross-tenant query
     * (super-admin in global view), so we drop the scope clause and return
     * every item addressed to the recipient. The {@code recipientUser.id}
     * predicate stays the load-bearing access control — the recipient is
     * always the calling user.
     */
    @Query(value = """
        SELECT i FROM InBasketItem i
        LEFT JOIN FETCH i.patient
        LEFT JOIN FETCH i.encounter
        WHERE i.recipientUser.id = :userId
          AND (:hospitalId IS NULL OR i.hospital.id = :hospitalId)
          AND (:type IS NULL OR i.itemType = :type)
          AND (:status IS NULL OR i.status = :status)
        ORDER BY
          CASE i.priority
            WHEN com.example.hms.enums.InBasketPriority.CRITICAL THEN 0
            WHEN com.example.hms.enums.InBasketPriority.URGENT   THEN 1
            ELSE 2
          END,
          i.createdAt DESC
    """, countQuery = """
        SELECT COUNT(i) FROM InBasketItem i
        WHERE i.recipientUser.id = :userId
          AND (:hospitalId IS NULL OR i.hospital.id = :hospitalId)
          AND (:type IS NULL OR i.itemType = :type)
          AND (:status IS NULL OR i.status = :status)
    """)
    Page<InBasketItem> findByRecipientFiltered(
        @Param("userId") UUID userId,
        @Param("hospitalId") UUID hospitalId,
        @Param("type") InBasketItemType type,
        @Param("status") InBasketItemStatus status,
        Pageable pageable
    );

    /**
     * Count items by status for the summary badge. {@code hospitalId} is
     * optional for the same super-admin global-view reason as
     * {@link #findByRecipientFiltered}.
     */
    @Query("""
        SELECT COUNT(i) FROM InBasketItem i
        WHERE i.recipientUser.id = :userId
          AND (:hospitalId IS NULL OR i.hospital.id = :hospitalId)
          AND i.status = :status
    """)
    long countByRecipientForSummary(
        @Param("userId") UUID userId,
        @Param("hospitalId") UUID hospitalId,
        @Param("status") InBasketItemStatus status);

    /** Count items by status AND type. {@code hospitalId} is optional. */
    @Query("""
        SELECT COUNT(i) FROM InBasketItem i
        WHERE i.recipientUser.id = :userId
          AND (:hospitalId IS NULL OR i.hospital.id = :hospitalId)
          AND i.status = :status
          AND i.itemType = :itemType
    """)
    long countByRecipientAndTypeForSummary(
        @Param("userId") UUID userId,
        @Param("hospitalId") UUID hospitalId,
        @Param("status") InBasketItemStatus status,
        @Param("itemType") InBasketItemType itemType);

    /** Check if a duplicate in-basket item already exists for a reference. */
    boolean existsByReferenceIdAndReferenceTypeAndRecipientUser_Id(
        UUID referenceId, String referenceType, UUID recipientUserId);
}
