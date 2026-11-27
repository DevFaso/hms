package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** D3943: the other side of an inter-store movement is written in the caller's transaction. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StockTransferBooking {
    private final PharmacyRepository pharmacies;
    private final InventoryItemRepository items;
    private final StockLotRepository lots;
    private final StockTransactionRepository transactions;
    private final EntityManager entityManager;
    private final FefoLotPolicy fefoLotPolicy;
    private final PharmacyPriceHistory priceHistory;
    private final com.example.hms.service.AuditEventLogService auditEventLogService;

    public record Route(Pharmacy destination, StockTransaction originalReceipt, String fefoOverrideReason) {
        public Route(Pharmacy destination, StockTransaction originalReceipt) {
            this(destination, originalReceipt, null);
        }
    }

    /** Serialise store pairs in the same order before any stock row is changed. */
    public Route prepare(StockTransactionRequestDTO request, InventoryItem source) {
        try {
            Route route = prepareRoute(request, source);
            if (route != null) {
                lockItem(source.getId());
                if (request.getStockLotId() != null) {
                    lockLot(request.getStockLotId());
                }
            }
            return route;
        } catch (org.springframework.dao.DataAccessException busy) {
            throw lockFailure(busy);
        }
    }

    private Route prepareRoute(StockTransactionRequestDTO request, InventoryItem source) {
        if (request.getTransactionType() == StockTransactionType.TRANSFER) {
            UUID destinationId = request.getReferenceId();
            if (destinationId == null || destinationId.equals(source.getPharmacy().getId())) {
                throw refused("stockTransaction.transfer.destinationRequired");
            }
            return new Route(lockStores(source.getPharmacy(), destinationId), null);
        }
        if (request.getTransactionType() == StockTransactionType.RETURN && request.getReferenceId() != null) {
            StockTransaction original = transactions.findById(request.getReferenceId())
                .filter(row -> row.getTransactionType() == StockTransactionType.TRANSFER)
                .filter(row -> row.getInventoryItem().getId().equals(source.getId())).orElse(null);
            if (original == null) {
                return null;
            }
            StockTransaction receipt = transactions.findByTransferOriginId(original.getId())
                .filter(row -> row.getTransactionType() == StockTransactionType.RECEIPT)
                .filter(row -> row.getInventoryItem() != null && row.getInventoryItem().getPharmacy() != null)
                .filter(row -> Objects.equals(row.getInventoryItem().getPharmacy().getId(), original.getReferenceId()))
                .filter(row -> Objects.equals(row.getInventoryItem().getMedicationCatalogItem().getId(),
                    original.getInventoryItem().getMedicationCatalogItem().getId()))
                .orElseThrow(() -> refused("stockTransaction.transfer.destinationUnavailable"));
            Pharmacy destination = receipt.getInventoryItem().getPharmacy();
            return new Route(lockStores(source.getPharmacy(), destination.getId()), receipt, request.getFefoOverrideReason());
        }
        return null;
    }

    private Pharmacy lockStores(Pharmacy source, UUID destinationId) {
        UUID hospitalId = source.getHospital().getId();
        List<UUID> ordered = java.util.stream.Stream.of(source.getId(), destinationId).distinct().sorted().toList();
        Pharmacy destination = null;
        for (UUID id : ordered) {
            Pharmacy locked = pharmacies.findLockedByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> refused("stockTransaction.transfer.destinationUnavailable"));
            if (!locked.isActive()) {
                throw refused("stockTransaction.transfer.destinationUnavailable");
            }
            if (id.equals(destinationId)) {
                // QA D4182 (r81): hospital stock moves between the hospital's own stores only.
                PharmacyStores.requireOwnStore(locked, "referenceId");
                destination = locked;
            }
        }
        return destination;
    }

    public void bookCounterpart(Route route, StockTransaction movement) {
        try {
            book(route, movement);
        } catch (org.springframework.dao.DataAccessException busy) {
            throw lockFailure(busy);
        }
    }

    private void book(Route route, StockTransaction movement) {
        if (movement.getTransactionType() == StockTransactionType.TRANSFER) {
            if (route == null) {
                throw refused("stockTransaction.transfer.destinationRequired");
            }
            receive(route.destination(), movement);
        } else if (route != null && route.originalReceipt() != null) {
            takeBack(route.originalReceipt(), movement, route.fefoOverrideReason());
        }
    }

    private void receive(Pharmacy destination, StockTransaction source) {
        InventoryItem item = destinationItem(destination, source.getInventoryItem(), source);
        WholeUnitQuantity.require(item, source.getQuantity(), WholeUnitQuantity.DEFAULT_KEY);
        StockLot lot = destinationLot(item, source);
        if (items.incrementOnHand(item.getId(), source.getQuantity(), LocalDateTime.now()) != 1) {
            throw refused("stockTransaction.transfer.destinationUnavailable");
        }
        StockTransaction receipt = StockTransaction.builder()
            .inventoryItem(item).stockLot(lot).transactionType(StockTransactionType.RECEIPT)
            .quantity(source.getQuantity()).referenceId(source.getId()).transferOriginId(source.getId()).reason(source.getReason())
            .performedByUser(source.getPerformedByUser()).witnessedByUser(source.getWitnessedByUser()).build();
        if (lot != null) {
            receipt.setLotNumber(lot.getLotNumber());
            receipt.setExpiryDate(lot.getExpiryDate());
            receipt.setUnitCost(lot.getUnitCost());
            receipt.setSupplier(source.getStockLot().getSupplier());
        }
        entityManager.refresh(item);
        // The self-FK must see the immutable origin even when insert batching reorders UUIDs.
        transactions.flush();
        transactions.save(receipt);
    }

    private InventoryItem destinationItem(Pharmacy destination, InventoryItem source, StockTransaction movement) {
        InventoryItem item = items.findByPharmacyIdAndMedicationCatalogItemId(
            destination.getId(), source.getMedicationCatalogItem().getId()).orElse(null);
        if (item == null) {
            String key = InventoryServiceImpl.sameDrugKey(source.getMedicationCatalogItem());
            if (key != null && items.findAllWithCatalogItemByPharmacyId(destination.getId()).stream()
                    .anyMatch(held -> key.equals(InventoryServiceImpl.sameDrugKey(held.getMedicationCatalogItem())))) {
                // Do not split stock or silently substitute a differently classified catalogue row.
                throw new FieldBusinessException("referenceId", "stock.inventory.duplicate");
            }
            // QA D4181 (r81): the item a transfer creates is sold as its source is: same unit, same pack size,
            // same selling price (or the same documented free-of-charge reason). It used to arrive with no price,
            // and a fill from it was charged nothing.
            item = InventoryItem.builder().pharmacy(destination).medicationCatalogItem(source.getMedicationCatalogItem())
                .unit(source.getUnit()).unitsPerPack(source.getUnitsPerPack()).packUnit(source.getPackUnit())
                .packSizeSetAt(source.getPackSizeSetAt()).freeOfChargeReason(source.getFreeOfChargeReason()).build();
            item = items.saveAndFlush(item);
            keepCopiedFreeOfCharge(item, movement);
            carryPrice(item, source, movement);
            return item;
        }
        item = lockItem(item.getId());
        // The transfer is in the source's stock units; a different pack size needs an explicit conversion first.
        if (!item.isActive() || !Objects.equals(item.getUnit(), source.getUnit())
                || !Objects.equals(item.getUnitsPerPack(), source.getUnitsPerPack())) {
            throw refused("stockTransaction.transfer.unitsDiffer");
        }
        // An item the destination holds but never priced takes the source's price; a price it set stays its own,
        // a price of 0 included (review of #1380: 0 set on purpose is not "no price").
        carryPrice(item, source, movement);
        return item;
    }

    /** The reason kept in the price history when a transfer gives its destination item the source's price. */
    static final String TRANSFER_PRICE_REASON = "Prix repris de la pharmacie d'origine du transfert";

    /** The reason kept when a transfer creates its destination item documented as free, as its source is. */
    static final String TRANSFER_FREE_REASON_PREFIX = "Gratuité reprise de la pharmacie d'origine du transfert : ";

    /**
     * QA D4181 (r81): gives {@code item} the selling price of {@code source} when it has no price at all (null) and
     * is not documented as free, and keeps the change in the price history under the person who moved the stock,
     * with the same audit event as a price set on the item form. Review of #1380: a price the store set, 0
     * included, is its own and is never overwritten.
     */
    private void carryPrice(InventoryItem item, InventoryItem source, StockTransaction movement) {
        BigDecimal price = PharmacyListPrice.of(source).orElse(null);
        if (price == null || PharmacyListPrice.isFree(item) || item.getUnitPrice() != null) {
            return;
        }
        item.setUnitPrice(price);
        items.saveAndFlush(item);
        keepChange(item, null, price, TRANSFER_PRICE_REASON, movement);
    }

    /**
     * Review of #1380 (F4): an item a transfer creates documented as free, as its source is, keeps that in its
     * price history and in the audit log like a free-of-charge reason set on the item form.
     */
    private void keepCopiedFreeOfCharge(InventoryItem item, StockTransaction movement) {
        if (PharmacyListPrice.isFree(item)) {
            keepChange(item, null, null, TRANSFER_FREE_REASON_PREFIX + item.getFreeOfChargeReason(), movement);
        }
    }

    /** The price-history row (when somebody is named on the movement) and the audit event of a transfer's change. */
    private void keepChange(InventoryItem item, BigDecimal before, BigDecimal after, String reason,
                            StockTransaction movement) {
        UUID movedBy = movement.getPerformedByUser() != null ? movement.getPerformedByUser().getId() : null;
        if (movedBy != null) {
            priceHistory.keep(item, before, after, reason, movedBy, LocalDateTime.now());
        }
        try {
            // Ids only: the prices and the reason stay on the history row.
            auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                .userId(movedBy)
                .hospitalId(item.getPharmacy() != null && item.getPharmacy().getHospital() != null
                    ? item.getPharmacy().getHospital().getId() : null)
                .eventType(com.example.hms.enums.AuditEventType.PHARMACY_PRICE_CHANGED)
                .eventDescription("Pharmacy selling price changed")
                .status(com.example.hms.enums.AuditStatus.SUCCESS)
                .resourceId(item.getId() != null ? item.getId().toString() : null)
                .entityType("INVENTORY")
                .build());
        } catch (RuntimeException e) {
            // The audit never undoes the stock movement it describes.
            org.slf4j.LoggerFactory.getLogger(StockTransferBooking.class)
                .warn("Failed to log audit event PHARMACY_PRICE_CHANGED: {}", e.getMessage());
        }
    }

    private StockLot destinationLot(InventoryItem item, StockTransaction source) {
        StockLot original = source.getStockLot();
        if (original == null) {
            if (lots.existsByInventoryItemId(item.getId())) {
                throw refused("stockTransaction.transfer.lotRequired");
            }
            return null;
        }
        if (original.getExpiryDate() == null || !original.getExpiryDate().isAfter(LocalDate.now())) {
            throw refused("stockTransaction.transfer.expiredLot");
        }
        StockLotBalance.requireReconciled(item, lots.sumRemainingForItem(item.getId()));
        if (lots.existsOtherExpiryForLot(item.getId(), original.getLotNumber(), original.getExpiryDate())) {
            throw refused("stockTransaction.transfer.lotDiffers");
        }
        List<StockLot> matching = lots.findSameLot(item.getId(), original.getLotNumber(), original.getExpiryDate());
        if (matching.size() > 1) {
            throw refused("stockTransaction.transfer.lotDiffers");
        }
        if (!matching.isEmpty()) {
            StockLot lot = lockLot(matching.getFirst().getId());
            if (!sameCost(lot.getUnitCost(), original.getUnitCost())) {
                throw refused("stockTransaction.transfer.lotDiffers");
            }
            if (lots.addReceipt(lot.getId(), source.getQuantity(), LocalDateTime.now()) != 1) {
                throw refused("stockTransaction.transfer.destinationUnavailable");
            }
            entityManager.refresh(lot);
            return lot;
        }
        return lots.saveAndFlush(StockLot.builder().inventoryItem(item).lotNumber(original.getLotNumber())
            .expiryDate(original.getExpiryDate()).initialQuantity(source.getQuantity()).remainingQuantity(source.getQuantity())
            .unitCost(original.getUnitCost()).supplier(original.getSupplier()).receivedDate(LocalDate.now())
            .receivedByUser(source.getPerformedByUser()).build());
    }

    private void takeBack(StockTransaction receipt, StockTransaction returned, String fefoOverrideReason) {
        BigDecimal quantity = returned.getQuantity();
        LocalDateTime now = LocalDateTime.now();
        StockLot lot = receipt.getStockLot();
        InventoryItem item = receipt.getInventoryItem();
        lockItem(item.getId());
        if (lot != null) {
            lockLot(lot.getId());
        }
        InventoryItem source = returned.getInventoryItem();
        if (!Objects.equals(item.getUnit(), source.getUnit())
                || !Objects.equals(item.getUnitsPerPack(), source.getUnitsPerPack())) {
            throw refused("stockTransaction.transfer.unitsDiffer");
        }
        WholeUnitQuantity.require(item, quantity, WholeUnitQuantity.DEFAULT_KEY);
        if (lot == null) {
            StockLotBalance.requireLotlessReturn(lots.existsByInventoryItemId(item.getId()));
        }
        String override = lot == null ? null : fefoLotPolicy.requireFefoOrReason(lot, fefoOverrideReason);
        if (lot != null && lots.decrementRemaining(lot.getId(), quantity, now) != 1) {
            throw refused("stockTransaction.transfer.destinationInsufficient");
        }
        if (items.decrementOnHand(item.getId(), quantity, now) != 1) {
            throw refused("stockTransaction.transfer.destinationInsufficient");
        }
        entityManager.refresh(item);
        if (lot != null) {
            entityManager.refresh(lot);
        }
        // Keep an explicit FEFO override on the receiving store's ledger too.
        String reason = override == null ? returned.getReason() : returned.getReason() + " | FEFO: " + override;
        if (reason != null && reason.length() > 500) {
            throw new FieldBusinessException("fefoOverrideReason", "pharmacy.fefo.reasonTooLong",
                String.valueOf(Math.max(0, 500 - returned.getReason().length() - " | FEFO: ".length())));
        }
        // A signed adjustment records the receiving store's return without inventing another outbound transfer.
        transactions.save(StockTransaction.builder().inventoryItem(item).stockLot(lot)
            .transactionType(StockTransactionType.ADJUSTMENT).quantity(quantity.negate())
            .referenceId(returned.getId()).reason(reason).performedByUser(returned.getPerformedByUser())
            .witnessedByUser(returned.getWitnessedByUser()).build());
    }

    private static RuntimeException lockFailure(org.springframework.dao.DataAccessException failure) {
        if (StockRowLocks.isContention(failure)) {
            return refused("stockTransaction.transfer.stockBusy", failure);
        }
        return failure;
    }

    private InventoryItem lockItem(UUID id) {
        InventoryItem item = items.tryLockById(id)
            .orElseThrow(() -> refused("stockTransaction.transfer.destinationUnavailable"));
        entityManager.refresh(item);
        return item;
    }

    private StockLot lockLot(UUID id) {
        StockLot lot = lots.tryLockById(id)
            .orElseThrow(() -> refused("stockTransaction.transfer.destinationUnavailable"));
        entityManager.refresh(lot);
        return lot;
    }

    private static boolean sameCost(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static FieldBusinessException refused(String key) {
        return new FieldBusinessException("referenceId", key);
    }

    private static FieldBusinessException refused(String key, Throwable cause) {
        FieldBusinessException refusal = refused(key);
        refusal.initCause(cause);
        return refusal;
    }
}
