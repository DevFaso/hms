package com.example.hms.service.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.PharmacyPriceChange;
import com.example.hms.repository.pharmacy.PharmacyPriceChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Review of #1239 (V311): the one owner of a pharmacy's price history. Every writer of
 * {@code InventoryItem.unitPrice} (the item form, the pack-size conversion) records its change here, and the
 * reconciliation asks here what the price was when a sale was made.
 */
@Component
@RequiredArgsConstructor
public class PharmacyPriceHistory {

    private final PharmacyPriceChangeRepository repository;

    /** Whether two prices are the same price; a missing one and zero both mean "not priced". */
    public static boolean samePrice(BigDecimal a, BigDecimal b) {
        return priced(a).compareTo(priced(b)) == 0;
    }

    private static BigDecimal priced(BigDecimal price) {
        return price == null ? BigDecimal.ZERO : price;
    }

    /** Keeps one change of {@code item}'s price: old, new, why, who and when. */
    public PharmacyPriceChange keep(InventoryItem item, BigDecimal oldPrice, BigDecimal newPrice, String reason,
                                    UUID changedBy, LocalDateTime changedAt) {
        return repository.save(PharmacyPriceChange.builder()
                .hospitalId(item.getPharmacy().getHospital().getId())
                .pharmacyId(item.getPharmacy().getId())
                .inventoryItemId(item.getId())
                .oldPrice(oldPrice)
                .newPrice(newPrice)
                .reason(reason.length() > 500 ? reason.substring(0, 500) : reason)
                .changedBy(changedBy)
                .changedAt(changedAt)
                .build());
    }

    /**
     * The price of one unit of {@code item} in force at {@code at}: the new price of the last change made by then;
     * else the old price of the first change made after it; else (no history at all, an item priced before V311)
     * the price it has now. Empty when the item was not priced then.
     */
    public Optional<BigDecimal> priceAt(InventoryItem item, LocalDateTime at) {
        if (item == null || item.getId() == null || at == null) {
            return PharmacyListPrice.of(item);
        }
        Optional<PharmacyPriceChange> before = repository
                .findFirstByInventoryItemIdAndChangedAtLessThanEqualOrderByChangedAtDescCreatedAtDesc(item.getId(), at);
        if (before.isPresent()) {
            return positive(before.get().getNewPrice());
        }
        Optional<PharmacyPriceChange> after = repository
                .findFirstByInventoryItemIdAndChangedAtGreaterThanOrderByChangedAtAscCreatedAtAsc(item.getId(), at);
        if (after.isPresent()) {
            return positive(after.get().getOldPrice());
        }
        return PharmacyListPrice.of(item);
    }

    /** An item's whole price history, oldest first. The caller has scoped the item to its hospital. */
    public java.util.List<PharmacyPriceChange> historyOf(InventoryItem item) {
        return repository.findByInventoryItemIdOrderByChangedAtAscCreatedAtAsc(item.getId());
    }

    private static Optional<BigDecimal> positive(BigDecimal price) {
        return price == null || price.signum() <= 0 ? Optional.empty() : Optional.of(price);
    }
}
