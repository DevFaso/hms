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
            StockTransaction receipt = transactions.findByReferenceIdAndTransactionType(
                request.getReferenceId(), StockTransactionType.RECEIPT).orElse(null);
            if (receipt != null) {
                Pharmacy destination = receipt.getInventoryItem().getPharmacy();
                return new Route(lockStores(source.getPharmacy(), destination.getId()), receipt, request.getFefoOverrideReason());
            }
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
        InventoryItem item = destinationItem(destination, source.getInventoryItem());
        WholeUnitQuantity.require(item, source.getQuantity(), WholeUnitQuantity.DEFAULT_KEY);
        StockLot lot = destinationLot(item, source);
        if (items.incrementOnHand(item.getId(), source.getQuantity(), LocalDateTime.now()) != 1) {
            throw refused("stockTransaction.transfer.destinationUnavailable");
        }
        StockTransaction receipt = StockTransaction.builder()
            .inventoryItem(item).stockLot(lot).transactionType(StockTransactionType.RECEIPT)
            .quantity(source.getQuantity()).referenceId(source.getId()).reason(source.getReason())
            .performedByUser(source.getPerformedByUser()).witnessedByUser(source.getWitnessedByUser()).build();
        if (lot != null) {
            receipt.setLotNumber(lot.getLotNumber());
            receipt.setExpiryDate(lot.getExpiryDate());
            receipt.setUnitCost(lot.getUnitCost());
            receipt.setSupplier(lot.getSupplier());
        }
        entityManager.refresh(item);
        transactions.save(receipt);
    }

    private InventoryItem destinationItem(Pharmacy destination, InventoryItem source) {
        InventoryItem item = items.findByPharmacyIdAndMedicationCatalogItemId(
            destination.getId(), source.getMedicationCatalogItem().getId()).orElse(null);
        if (item == null) {
            String key = InventoryServiceImpl.sameDrugKey(source.getMedicationCatalogItem());
            if (key != null && items.findAllWithCatalogItemByPharmacyId(destination.getId()).stream()
                    .anyMatch(held -> key.equals(InventoryServiceImpl.sameDrugKey(held.getMedicationCatalogItem())))) {
                // Do not split stock or silently substitute a differently classified catalogue row.
                throw new FieldBusinessException("referenceId", "stock.inventory.duplicate");
            }
            item = InventoryItem.builder().pharmacy(destination).medicationCatalogItem(source.getMedicationCatalogItem())
                .unit(source.getUnit()).unitsPerPack(source.getUnitsPerPack()).packUnit(source.getPackUnit())
                .packSizeSetAt(source.getPackSizeSetAt()).build();
            return items.saveAndFlush(item);
        }
        item = lockItem(item.getId());
        // The transfer is in the source's stock units; a different pack size needs an explicit conversion first.
        if (!item.isActive() || !Objects.equals(item.getUnit(), source.getUnit())
                || !Objects.equals(item.getUnitsPerPack(), source.getUnitsPerPack())) {
            throw refused("stockTransaction.transfer.unitsDiffer");
        }
        return item;
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
