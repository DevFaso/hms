package com.example.hms.repository;

import com.example.hms.model.PriceListPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 2 (V302): price versions. Insert-only from the API: there is no update or delete path, and
 * the service sets {@code effective_to} only under the item's lock ({@code PriceListItemRepository.lockByIdAtHospital}).
 */
public interface PriceListPriceRepository extends JpaRepository<PriceListPrice, UUID> {

    /** Every version of one item at its hospital, newest start first (replaced ones included). */
    @Query("""
        SELECT p FROM PriceListPrice p
        WHERE p.item.id = :itemId AND p.hospitalId = :hospitalId
        ORDER BY p.effectiveFrom DESC, p.createdAt DESC
        """)
    List<PriceListPrice> findHistory(@Param("itemId") UUID itemId, @Param("hospitalId") UUID hospitalId);

    /** Every version of several items, for the list screen in one query. */
    @Query("""
        SELECT p FROM PriceListPrice p
        WHERE p.item.id IN :itemIds
        ORDER BY p.effectiveFrom DESC, p.createdAt DESC
        """)
    List<PriceListPrice> findByItemIds(@Param("itemIds") Collection<UUID> itemIds);
}
