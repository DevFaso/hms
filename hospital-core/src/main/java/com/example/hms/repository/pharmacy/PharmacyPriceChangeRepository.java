package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacyPriceChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Review of #1239 (V311): the price history of an inventory item. Every read names the item, which the caller has
 * already scoped to its hospital.
 */
public interface PharmacyPriceChangeRepository extends JpaRepository<PharmacyPriceChange, UUID> {

    /** The last change made at or before a moment: its new price was in force then. */
    Optional<PharmacyPriceChange> findFirstByInventoryItemIdAndChangedAtLessThanEqualOrderByChangedAtDescCreatedAtDesc(
            UUID inventoryItemId, LocalDateTime at);

    /** The first change made after a moment: its old price was in force until then. */
    Optional<PharmacyPriceChange> findFirstByInventoryItemIdAndChangedAtGreaterThanOrderByChangedAtAscCreatedAtAsc(
            UUID inventoryItemId, LocalDateTime at);

    /** An item's whole price history, oldest first. */
    List<PharmacyPriceChange> findByInventoryItemIdOrderByChangedAtAscCreatedAtAsc(UUID inventoryItemId);
}
