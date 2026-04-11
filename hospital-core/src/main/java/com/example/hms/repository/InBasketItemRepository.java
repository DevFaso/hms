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
     * Paginated list for a recipient, filtered by optional type and status.
     */
    @Query("""
        SELECT i FROM InBasketItem i
        LEFT JOIN FETCH i.patient
        LEFT JOIN FETCH i.encounter
        WHERE i.recipientUser.id = :userId
          AND i.hospital.id = :hospitalId
          AND (:type IS NULL OR i.itemType = :type)
          AND (:status IS NULL OR i.status = :status)
        ORDER BY
          CASE i.priority
            WHEN com.example.hms.enums.InBasketPriority.CRITICAL THEN 0
            WHEN com.example.hms.enums.InBasketPriority.URGENT   THEN 1
            ELSE 2
          END,
          i.createdAt DESC
    """)
    Page<InBasketItem> findByRecipientFiltered(
        @Param("userId") UUID userId,
        @Param("hospitalId") UUID hospitalId,
        @Param("type") InBasketItemType type,
        @Param("status") InBasketItemStatus status,
        Pageable pageable
    );

    /** Count unread items by type for the summary badge. */
    long countByRecipientUser_IdAndHospital_IdAndStatus(
        UUID recipientUserId, UUID hospitalId, InBasketItemStatus status);

    long countByRecipientUser_IdAndHospital_IdAndStatusAndItemType(
        UUID recipientUserId, UUID hospitalId, InBasketItemStatus status, InBasketItemType itemType);

    /** Check if a duplicate in-basket item already exists for a reference. */
    boolean existsByReferenceIdAndReferenceTypeAndRecipientUser_Id(
        UUID referenceId, String referenceType, UUID recipientUserId);
}
