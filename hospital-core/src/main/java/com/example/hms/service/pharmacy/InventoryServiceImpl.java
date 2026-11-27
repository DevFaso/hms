package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.InventoryItemMapper;
import com.example.hms.mapper.pharmacy.StockLotMapper;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import com.example.hms.validation.PickedDayNotInFutureValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    /** QA D733: the most one goods receipt may count in (per lot line). */
    static final BigDecimal MAX_RECEIPT_QUANTITY = new BigDecimal("1000000");


    private final InventoryItemRepository inventoryItemRepository;
    private final StockLotRepository stockLotRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final PharmacyRepository pharmacyRepository;
    private final MedicationCatalogItemRepository medicationCatalogItemRepository;
    private final UserRepository userRepository;
    private final InventoryItemMapper inventoryItemMapper;
    private final StockLotMapper stockLotMapper;
    private final StockTransactionMapper stockTransactionMapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    private final LowStockAlertDelivery lowStockAlertDelivery;
    private final MessageSource messageSource;
    /** QA D655: a controlled substance is received in front of a witness. */
    private final ControlledStockWitness controlledStockWitness;
    /** Re-reads an entity after an atomic quantity UPDATE (#825 round 5). */
    private final EntityManager entityManager;
    /** The server's clock (UTC), for the picked-day check of a receipt. */
    private final Clock clock;
    /** Review of #1239: every change of a selling price is kept, with its reason. */
    private final PharmacyPriceHistory priceHistory;

    // ── Inventory items ──────────────────────────────────────────────────

    @Override
    @Transactional
    public InventoryItemResponseDTO createInventoryItem(InventoryItemRequestDTO dto) {
        Pharmacy pharmacy = resolvePharmacy(dto.getPharmacyId());
        enforceHospitalScope(pharmacy);
        // D3943: transfer-created items and manually created items share this store lock.
        pharmacyRepository.findLockedByIdAndHospitalId(pharmacy.getId(), pharmacy.getHospital().getId())
            .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacy.getId()));

        MedicationCatalogItem medication = resolveMedication(dto.getMedicationCatalogItemId());

        inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(
                pharmacy.getId(), medication.getId())
            .ifPresent(existing -> {
                throw new com.example.hms.exception.FieldBusinessException("medicationCatalogItemId", "stock.inventory.duplicate");
            });
        // QA D208: the same drug under another catalogue row (same molecule,
        // strength and form) is the same stock item; a second one splits the
        // count and doubles every picker.
        String key = sameDrugKey(medication);
        if (key != null) {
            boolean twin = inventoryItemRepository.findByPharmacyIdAndActiveTrue(pharmacy.getId(),
                    org.springframework.data.domain.Pageable.unpaged()).stream()
                .anyMatch(existing -> key.equals(sameDrugKey(existing.getMedicationCatalogItem())));
            if (twin) {
                throw new com.example.hms.exception.FieldBusinessException("medicationCatalogItemId", "stock.inventory.duplicate");
            }
        }
        // QA D208: stock enters through a goods receipt (lot + ledger), never
        // as an opening number typed on the item.
        if (dto.getQuantityOnHand() != null && dto.getQuantityOnHand().signum() != 0) {
            throw new com.example.hms.exception.FieldBusinessException("quantityOnHand", "stock.inventory.openingQuantity");
        }

        // Review of #1239: a price is set by the store manager or a hospital admin, and kept in the history.
        PriceChange priceChange = priceChangeOf(null, dto, pharmacy);
        InventoryItem entity = inventoryItemMapper.toEntity(dto, pharmacy, medication);
        entity.setQuantityOnHand(java.math.BigDecimal.ZERO);
        InventoryItem saved = inventoryItemRepository.save(entity);
        keepPriceChange(saved, priceChange);
        return inventoryItemMapper.toResponseDTO(saved);
    }

    /** A price change asked for on the item form, checked: the old and the new price, why and by whom. */
    private record PriceChange(BigDecimal oldPrice, BigDecimal newPrice, String reason, UUID changedBy) {
    }

    /** The reason kept when an item is created already priced (nothing changed: there was no price). */
    static final String INITIAL_PRICE_REASON = "Prix initial";
    private static final String FIELD_PRICE_REASON = "priceChangeReason";

    /**
     * Review of #1239 (list price changes): the selling price is what every sale, charge and refund is computed
     * from, and a pharmacist could rewrite it with no reason and no trace. A request that changes it (a price on a
     * new item, or another price on an existing one) needs a store-manager or hospital-admin assignment at the
     * pharmacy's hospital and, on an existing item, a reason. Null when the request leaves the price as it is, so a
     * pharmacist still edits the thresholds and the unit with the unchanged price in the form.
     */
    private PriceChange priceChangeOf(InventoryItem existing, InventoryItemRequestDTO dto, Pharmacy pharmacy) {
        BigDecimal current = existing != null ? existing.getUnitPrice() : null;
        if (dto.getUnitPrice() == null || PharmacyPriceHistory.samePrice(current, dto.getUnitPrice())) {
            return null;
        }
        UUID hospitalId = pharmacy != null && pharmacy.getHospital() != null ? pharmacy.getHospital().getId() : null;
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null || !roleValidator.canSetPharmacyPrice(userId, hospitalId)) {
            throw new com.example.hms.exception.FieldBusinessException("unitPrice", "pharmacy.price.roleRequired");
        }
        String reason = trimmedOrEmpty(dto.getPriceChangeReason());
        if (reason.isEmpty()) {
            if (existing != null) {
                throw new com.example.hms.exception.FieldBusinessException(FIELD_PRICE_REASON,
                        "pharmacy.price.reasonRequired");
            }
            reason = INITIAL_PRICE_REASON;
        }
        if (reason.length() > 500) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_PRICE_REASON,
                    "pharmacy.price.reasonTooLong");
        }
        com.example.hms.utility.CardNumbers.requireNone(reason, FIELD_PRICE_REASON);
        return new PriceChange(current, dto.getUnitPrice(), reason, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.example.hms.payload.dto.pharmacy.PharmacyPriceChangeDTO> listPriceHistory(UUID id) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        return priceHistory.historyOf(item).stream()
                .map(change -> com.example.hms.payload.dto.pharmacy.PharmacyPriceChangeDTO.builder()
                        .id(change.getId())
                        .inventoryItemId(change.getInventoryItemId())
                        .oldPrice(change.getOldPrice())
                        .newPrice(change.getNewPrice())
                        .reason(change.getReason())
                        .changedBy(change.getChangedBy())
                        .changedByName(userRepository.findById(change.getChangedBy())
                                .map(u -> ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                                        + (u.getLastName() != null ? u.getLastName() : "")).trim())
                                .orElse(null))
                        .changedAt(change.getChangedAt())
                        .build())
                .toList();
    }

    /** Writes the price-history row and the audit event of a change; nothing when the price did not change. */
    private void keepPriceChange(InventoryItem item, PriceChange change) {
        if (change == null) {
            return;
        }
        priceHistory.keep(item, change.oldPrice(), change.newPrice(), change.reason(), change.changedBy(),
                java.time.LocalDateTime.now(clock));
        // Ids only: the prices and the reason stay on the history row.
        logAudit(AuditEventType.PHARMACY_PRICE_CHANGED, "Pharmacy selling price changed", item.getId().toString());
    }

    /**
     * QA D208: molecule + strength + form, accent- and case-insensitive, or
     * null when the catalogue row does not name its molecule.
     */
    static String sameDrugKey(MedicationCatalogItem medication) {
        if (medication == null) {
            return null;
        }
        String molecule = medication.getGenericName() != null && !medication.getGenericName().isBlank()
            ? medication.getGenericName() : medication.getNameFr();
        if (molecule == null || molecule.isBlank()) {
            return null;
        }
        return fold(molecule) + "|" + fold(medication.getStrength()) + "|" + fold(medication.getStrengthUnit())
            + "|" + fold(medication.getForm());
    }

    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemResponseDTO getInventoryItem(UUID id) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        return inventoryItemMapper.toResponseDTO(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InventoryItemResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        return inventoryItemRepository.findByPharmacyIdAndActiveTrue(pharmacyId, pageable)
                .map(inventoryItemMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InventoryItemResponseDTO> listByHospital(Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return inventoryItemRepository.findAll(pageable)
                    .map(inventoryItemMapper::toResponseDTO);
        }
        return inventoryItemRepository.findByPharmacyHospitalIdAndActiveTrue(hospitalId, pageable)
                .map(inventoryItemMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public InventoryItemResponseDTO updateInventoryItem(UUID id, InventoryItemRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        // QA D208: a quantity typed on the item moved stock with no ledger
        // row. A count correction is an ADJUSTMENT transaction (stock
        // adjustment screen), which writes the ledger and needs a reason.
        if (dto.getQuantityOnHand() != null && (item.getQuantityOnHand() == null
                || dto.getQuantityOnHand().compareTo(item.getQuantityOnHand()) != 0)) {
            throw new com.example.hms.exception.FieldBusinessException("quantityOnHand", "stock.inventory.quantityViaTransaction");
        }
        // Review of #1239: checked against the price the item has now, before the mapper overwrites it.
        PriceChange priceChange = priceChangeOf(item, dto, item.getPharmacy());
        inventoryItemMapper.updateEntity(item, dto);
        InventoryItem saved = inventoryItemRepository.save(item);
        keepPriceChange(saved, priceChange);
        return inventoryItemMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deactivateInventoryItem(UUID id, String reason) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        // QA D4197 (r81): 40 units on the shelf left the active inventory in one call, with no reason and no trace.
        // An item that still holds stock is deactivated only with its reason, and the refusal says what it holds.
        String why = reason == null ? "" : reason.trim();
        BigDecimal onHand = item.getQuantityOnHand() == null ? BigDecimal.ZERO : item.getQuantityOnHand();
        if (onHand.signum() > 0 && why.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "inventoryItem.deactivate.reasonRequired",
                com.example.hms.utility.DisplayNumbers.quantity(onHand));
        }
        if (why.length() > 300) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "inventoryItem.deactivate.reasonTooLong");
        }
        boolean wasActive = item.isActive();
        item.setActive(false);
        inventoryItemRepository.save(item);
        if (wasActive) {
            // Ids and the quantity only: the reason is the user's own text, kept as they wrote it.
            logDeactivation(item, onHand, why);
        }
    }

    /**
     * QA D2674: one ADJUSTMENT per lot (or for the item, when it has no lots) for what the conversion added, so the
     * ledger (and the controlled register's running balance) still equals the stock.
     */
    private void recordConversion(InventoryItem item, java.util.List<StockLot> lots, BigDecimal onHandBefore,
                                  BigDecimal factor, String reason) {
        String ledgerReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        BigDecimal gain = factor.subtract(BigDecimal.ONE);
        for (StockLot lot : lots) {
            BigDecimal before = lot.getRemainingQuantity() != null ? lot.getRemainingQuantity() : BigDecimal.ZERO;
            if (before.signum() > 0) {
                recordStockTransaction(item, lot, StockTransactionType.ADJUSTMENT, before.multiply(gain), ledgerReason, null);
            }
            refreshIfManaged(lot);
        }
        if (lots.isEmpty() && onHandBefore.signum() > 0) {
            recordStockTransaction(item, null, StockTransactionType.ADJUSTMENT, onHandBefore.multiply(gain), ledgerReason, null);
        }
    }

    /** QA D2674: the price of one unit is the pack's divided by the pack size; the thresholds count units. */
    private static void scaleItemFigures(InventoryItem item, BigDecimal factor) {
        if (item.getUnitPrice() != null) {
            item.setUnitPrice(item.getUnitPrice().divide(factor, 2, java.math.RoundingMode.HALF_UP));
        }
        if (item.getReorderThreshold() != null) {
            item.setReorderThreshold(item.getReorderThreshold().multiply(factor));
        }
        if (item.getReorderQuantity() != null) {
            item.setReorderQuantity(item.getReorderQuantity().multiply(factor));
        }
    }

    /** QA D2674 (r65): a delivery counted in packs of an item kept in units is received as units. */
    private static void receiveInUnits(InventoryItem item, StockLotRequestDTO dto) {
        if (!Boolean.TRUE.equals(dto.getInPacks())) {
            return;
        }
        dto.setInitialQuantity(PackQuantity.toUnits(item, dto.getInitialQuantity(), true, "initialQuantity"));
        if (dto.getRemainingQuantity() != null) {
            dto.setRemainingQuantity(PackQuantity.toUnits(item, dto.getRemainingQuantity(), true, "remainingQuantity"));
        }
        if (dto.getUnitCost() != null && item.getUnitsPerPack() != null) {
            dto.setUnitCost(dto.getUnitCost().divide(BigDecimal.valueOf(item.getUnitsPerPack()), 4,
                    java.math.RoundingMode.HALF_UP));
        }
        dto.setInPacks(false);
    }

    /**
     * The pack size asked for, checked: units per pack, the new unit, the pack's name, the reason, and whether the
     * stock is already counted in that unit (QA D3400) or in packs (converted).
     */
    private record PackSize(int perPack, String unit, String packUnit, String reason, boolean alreadyInUnits) {
    }

    /**
     * QA D3400 (r70): « Convertir le stock » multiplied the reorder threshold and order quantity of an item already
     * counted in tablets, so 50 tablets showed « Stock faible ». Asked explicitly, or read from the units: the stock is
     * already in units when the new unit is the item's current unit (accents, case and a plural « s » ignored).
     */
    static boolean alreadyInUnits(InventoryItem item, String newUnit, Boolean asked) {
        if (asked != null) {
            return asked;
        }
        return !trimmedOrEmpty(item.getUnit()).isEmpty() && sameUnit(item.getUnit(), newUnit);
    }

    private static boolean sameUnit(String a, String b) {
        return unitKey(a).equals(unitKey(b));
    }

    private static String unitKey(String unit) {
        String folded = fold(unit).replace("(s)", "").trim();
        return folded.endsWith("s") && folded.length() > 1 ? folded.substring(0, folded.length() - 1) : folded;
    }

    private static PackSize requirePackSize(InventoryItem item, com.example.hms.payload.dto.pharmacy.PackSizeRequestDTO dto) {
        if (item.getUnitsPerPack() != null) {
            throw new com.example.hms.exception.FieldBusinessException("unitsPerPack", "pharmacy.pack.alreadySet",
                    item.getUnitsPerPack(), item.getUnit());
        }
        // A controlled register is kept as received; its balance is never re-counted.
        if (ControlledStockWitness.isControlled(item)) {
            throw new com.example.hms.exception.FieldBusinessException("unitsPerPack", "pharmacy.pack.controlledRefused");
        }
        Integer perPack = dto != null ? dto.getUnitsPerPack() : null;
        String unit = trimmedOrEmpty(dto != null ? dto.getUnit() : null);
        String reason = trimmedOrEmpty(dto != null ? dto.getReason() : null);
        com.example.hms.exception.RequiredFields.check()
                .require(perPack != null && perPack >= 2 && perPack <= 10000, "unitsPerPack", "pharmacy.pack.sizeInvalid")
                .require(!unit.isEmpty(), "unit", "pharmacy.pack.unitRequired")
                .require(!reason.isEmpty(), "reason", "stockTransaction.reason.required")
                .orThrow();
        if (unit.length() > 60) {
            throw new com.example.hms.exception.FieldBusinessException("unit", "pharmacy.pack.unitTooLong");
        }
        // A pack converts to units that are counted, never to a measured amount (ml, mg).
        if (WholeUnitQuantity.isMeasured(unit)) {
            throw new com.example.hms.exception.FieldBusinessException("unit", "pharmacy.pack.unitMeasured");
        }
        String packUnit = trimmedOrEmpty(dto.getPackUnit());
        if (packUnit.isEmpty()) {
            packUnit = trimmedOrEmpty(item.getUnit());
        }
        if (packUnit.isEmpty()) {
            packUnit = "pack";
        }
        return new PackSize(perPack, unit, packUnit.length() > 60 ? packUnit.substring(0, 60) : packUnit, reason,
                alreadyInUnits(item, unit, dto.getAlreadyInUnits()));
    }

    private static BigDecimal nzQuantity(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String trimmedOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    @Transactional
    public InventoryItemResponseDTO setPackSize(UUID id, com.example.hms.payload.dto.pharmacy.PackSizeRequestDTO dto) {
        InventoryItem scoped = resolveInventoryItem(id);
        enforceHospitalScope(scoped.getPharmacy());
        // The item row locked: a fill, sale or receipt waits, then moves units, never packs.
        InventoryItem item = inventoryItemRepository.lockById(id)
                .orElseThrow(() -> new ResourceNotFoundException("inventoryItem.notFound", id));
        PackSize size = requirePackSize(item, dto);
        int perPack = size.perPack();
        String unit = size.unit();
        String packUnit = size.packUnit();
        String reason = size.reason();
        BigDecimal factor = BigDecimal.valueOf(perPack);
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
        if (size.alreadyInUnits()) {
            return declarePackSize(item, size);
        }
        // QA D3415 review: quarantined units are counted in the unit they were set aside in; a conversion would leave
        // the quarantine in packs while the item counts units. Destroy them first.
        BigDecimal quarantined = nzQuantity(stockTransactionRepository.sumQuantityForItem(item.getId(),
                java.util.EnumSet.of(com.example.hms.enums.StockTransactionType.QUARANTINE)))
                .subtract(nzQuantity(stockTransactionRepository.sumQuantityForItem(item.getId(),
                        java.util.EnumSet.of(com.example.hms.enums.StockTransactionType.DESTRUCTION))));
        if (quarantined.signum() > 0) {
            throw new com.example.hms.exception.FieldBusinessException("unitsPerPack", "pharmacy.pack.quarantineHeld");
        }
        java.util.List<StockLot> lots = stockLotRepository.findAllByInventoryItemIdOrderByExpiryDateAsc(item.getId());
        BigDecimal onHandBefore = item.getQuantityOnHand() != null ? item.getQuantityOnHand() : BigDecimal.ZERO;
        stockLotRepository.scaleQuantitiesOfItem(item.getId(), factor, now);
        inventoryItemRepository.scaleOnHand(item.getId(), factor, now);
        String conversionReason = "Conversion: 1 " + packUnit + " = " + perPack + " " + unit + " | " + reason;
        recordConversion(item, lots, onHandBefore, factor, conversionReason);
        refreshIfManaged(item);
        item.setUnitsPerPack(perPack);
        item.setPackUnit(packUnit);
        item.setUnit(unit);
        // Review of #1239: what was recorded before this moment counts packs; a refund converts.
        item.setPackSizeSetAt(now);
        BigDecimal priceBefore = item.getUnitPrice();
        scaleItemFigures(item, factor);
        InventoryItem saved = inventoryItemRepository.save(item);
        // The pack's price is unchanged, but the price of ONE unit is another figure from now on: the history
        // keeps it, so a sale made before the conversion is still compared with the price per pack.
        if (!PharmacyPriceHistory.samePrice(priceBefore, saved.getUnitPrice())) {
            priceHistory.keep(saved, priceBefore, saved.getUnitPrice(), conversionReason, resolveCurrentUser().getId(),
                    now);
        }
        logAudit(AuditEventType.STOCK_ADJUSTMENT, "Pack size set on an inventory item", saved.getId().toString());
        return inventoryItemMapper.toResponseDTO(saved);
    }

    /**
     * QA D3400: the stock is already counted in units; only the pack is declared. Nothing is multiplied (stock,
     * lots, threshold, order quantity, price per unit), no ledger row is written, and no conversion moment is kept,
     * so a refund never divides an earlier fill by the pack size.
     */
    private InventoryItemResponseDTO declarePackSize(InventoryItem item, PackSize size) {
        item.setUnitsPerPack(size.perPack());
        item.setPackUnit(size.packUnit());
        item.setUnit(size.unit());
        InventoryItem saved = inventoryItemRepository.save(item);
        logAudit(AuditEventType.STOCK_ADJUSTMENT, "Pack size set on an inventory item", saved.getId().toString());
        return inventoryItemMapper.toResponseDTO(saved);
    }

    // ── Stock lots ───────────────────────────────────────────────────────

    /** Field checks on a goods receipt, before anything is read or written. */
    private void validateReceiptQuantitiesAndDates(StockLotRequestDTO dto) {
        // D108: an expired lot is not stock. It was accepted at goods receipt
        // and counted on hand, where the FEFO picker skips it but every total,
        // reorder decision and report counted it.
        if (dto.getExpiryDate() != null && !dto.getExpiryDate().isAfter(LocalDate.now(ZoneId.systemDefault()))) {
            // QA D710: a lot expiring today is already expired.
            throw new com.example.hms.exception.FieldBusinessException("expiryDate", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.expired"));
        }

        // QA D341: a receipt adds stock; a zero or negative quantity reached
        // the database CHECK and came back as a generic constraint error.
        if (dto.getInitialQuantity() == null || dto.getInitialQuantity().signum() <= 0
            || (dto.getRemainingQuantity() != null && dto.getRemainingQuantity().signum() < 0)) {
            throw new com.example.hms.exception.FieldBusinessException("initialQuantity", "stockLot.quantity.positive");
        }
        // QA D733: 1e9 units were accepted; no single delivery is that size.
        if (dto.getInitialQuantity().compareTo(MAX_RECEIPT_QUANTITY) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("initialQuantity", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.tooLarge",
                    com.example.hms.utility.DisplayNumbers.quantity(MAX_RECEIPT_QUANTITY)));
        }
        // QA D334: a delivery cannot have been received in the future. #948
        // review: the portal defaults it to the reader's today, which east of
        // UTC is already tomorrow on the server's calendar, so that day passes.
        if (!PickedDayNotInFutureValidator.isAllowed(dto.getReceivedDate(), clock)) {
            throw new com.example.hms.exception.FieldBusinessException("receivedDate", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.receivedDate.future"));
        }
        // QA D405: a lot cannot be received holding more than was received.
        if (dto.getRemainingQuantity() != null && dto.getInitialQuantity() != null
            && dto.getRemainingQuantity().compareTo(dto.getInitialQuantity()) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("remainingQuantity", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.aboveReceived"));
        }
    }

    private void requireConsistentLotExpiry(InventoryItem item, StockLotRequestDTO dto) {
        // QA D315: a lot number identifies one manufacturing batch, which has
        // one expiry date. A second delivery of the same lot is fine; the same
        // lot number with another expiry is a typing error or a false label.
        if (dto.getLotNumber() != null && dto.getExpiryDate() != null
            && stockLotRepository.existsOtherExpiryForLot(item.getId(), dto.getLotNumber().trim(),
                dto.getExpiryDate())) {
            throw new com.example.hms.exception.FieldBusinessException("lotNumber", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.lotNumber.expiryMismatch"));
        }
    }

    @Override
    @Transactional
    public StockLotResponseDTO receiveStock(StockLotRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(dto.getInventoryItemId());
        enforceHospitalScope(item.getPharmacy());
        // Validate stock and pack metadata from the locked row, including legacy items not yet tracked by lot.
        inventoryItemRepository.lockById(item.getId());
        refreshIfManaged(item);
        receiveInUnits(item, dto);

        validateReceiptQuantitiesAndDates(dto);
        // QA D779: a receipt is a stock movement like any other (D668 guarded
        // only returns): 2.5 tablets left the item at 3001182.5 on hand.
        WholeUnitQuantity.require(item, dto.getInitialQuantity());
        WholeUnitQuantity.require(item, dto.getRemainingQuantity());
        // QA D778: the DTO constraint is the first gate; this one holds for
        // any caller that skips bean validation.
        if (dto.getUnitCost() != null && dto.getUnitCost().signum() < 0) {
            throw new com.example.hms.exception.FieldBusinessException("unitCost", "stockTransaction.unitCost.min");
        }
        // D3946: serialise validation with goods-in for this item; a concurrent receipt sees the committed batch.
        StockLotBalance.requireReconciled(item, stockLotRepository.sumRemainingForItem(item.getId()));
        if (dto.getLotNumber() != null) {
            dto.setLotNumber(dto.getLotNumber().trim());
        }
        requireConsistentLotExpiry(item, dto);
        List<StockLot> matching = stockLotRepository.findSameLot(item.getId(), dto.getLotNumber(), dto.getExpiryDate());
        if (matching.size() > 1) {
            throw new com.example.hms.exception.FieldBusinessException("lotNumber", "pharmacy.inventory.lot.duplicate");
        }

        if (!matching.isEmpty()) {
            StockLot existing = matching.getFirst();
            if (dto.getUnitCost() != null && (existing.getUnitCost() == null
                || dto.getUnitCost().compareTo(existing.getUnitCost()) != 0)) {
                throw new com.example.hms.exception.FieldBusinessException("unitCost", "pharmacy.inventory.lot.costMismatch");
            }
            if (dto.getRemainingQuantity() != null && dto.getRemainingQuantity().compareTo(dto.getInitialQuantity()) != 0) {
                throw new com.example.hms.exception.FieldBusinessException("remainingQuantity", "pharmacy.inventory.lot.partialTopUp");
            }
        }

        // The receipt is recorded against the signed-in user (D82): a
        // caller-supplied receiver let one user book a delivery in a
        // colleague's name.
        User receivedByUser = resolveCurrentUser();
        if (dto.getReceivedBy() != null && !dto.getReceivedBy().equals(receivedByUser.getId())) {
            throw new com.example.hms.exception.FieldBusinessException("receivedBy", 
                com.example.hms.utility.MessageUtil.resolve("stockTransaction.performer.notSelf"));
        }

        // QA D655: a controlled substance is received in front of a witness,
        // as every other movement of it is (checked before anything is written).
        User witness = ControlledStockWitness.isControlled(item)
            ? controlledStockWitness.resolve(dto.getWitnessedBy(), item, receivedByUser) : null;

        // A further delivery of the same unambiguous batch tops up its existing row (D733).
        BigDecimal received = matching.isEmpty() && dto.getRemainingQuantity() != null
            ? dto.getRemainingQuantity() : dto.getInitialQuantity();
        StockLot savedLot;
        if (matching.isEmpty()) {
            savedLot = stockLotRepository.saveAndFlush(stockLotMapper.toEntity(dto, item, receivedByUser));
        } else {
            savedLot = matching.getFirst();
            if (stockLotRepository.addReceipt(savedLot.getId(), received, LocalDateTime.now(ZoneId.systemDefault())) != 1) {
                throw new com.example.hms.exception.FieldBusinessException("lotNumber", "pharmacy.inventory.lot.duplicate");
            }
            refreshIfManaged(savedLot);
        }

        // Update quantity on hand: atomically, so a fill or a return of the
        // same item at the same moment is not overwritten (#825 round 5).
        inventoryItemRepository.incrementOnHand(item.getId(), received, LocalDateTime.now(ZoneId.systemDefault()));
        refreshIfManaged(item);

        // Record stock transaction
        recordStockTransaction(item, savedLot, StockTransactionType.RECEIPT,
                received, "Stock receipt: lot " + savedLot.getLotNumber(), witness, dto);

        // Audit
        logAudit(AuditEventType.STOCK_RECEIPT,
                "Stock received: " + received + " units, lot " + savedLot.getLotNumber(),
                savedLot.getId().toString());

        return stockLotMapper.toResponseDTO(savedLot);
    }

    @Override
    @Transactional(readOnly = true)
    public StockLotResponseDTO getStockLot(UUID id) {
        StockLot lot = resolveStockLot(id);
        enforceHospitalScope(lot.getInventoryItem().getPharmacy());
        return stockLotMapper.toResponseDTO(lot);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockLotResponseDTO> listLotsByInventoryItem(UUID inventoryItemId, Pageable pageable) {
        InventoryItem item = resolveInventoryItem(inventoryItemId);
        enforceHospitalScope(item.getPharmacy());
        return stockLotRepository.findByInventoryItemId(inventoryItemId, pageable)
                .map(stockLotMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockLotResponseDTO> listLotsByPharmacy(UUID pharmacyId, Pageable pageable) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        return stockLotRepository.findByInventoryItemPharmacyId(pharmacyId, pageable)
                .map(stockLotMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLotResponseDTO> getExpiringSoon(UUID pharmacyId, int daysAhead) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        LocalDate cutoff = LocalDate.now().plusDays(daysAhead);
        return stockLotRepository.findExpiringSoon(pharmacyId, cutoff).stream()
                .map(stockLotMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional
    public StockLotResponseDTO updateStockLot(UUID id, StockLotRequestDTO dto) {
        StockLot lot = resolveStockLot(id);
        enforceHospitalScope(lot.getInventoryItem().getPharmacy());
        BigDecimal remainingBefore = lot.getRemainingQuantity();
        // QA D334: the lot number and expiry are what was received and what
        // the FEFO pick and the expiry write-off run on. Changing them after
        // receipt left no ledger entry; a wrong lot is corrected by a
        // write-off and a new receipt, which the ledger records.
        if ((dto.getLotNumber() != null && !dto.getLotNumber().equals(lot.getLotNumber()))
            || (dto.getExpiryDate() != null && !dto.getExpiryDate().equals(lot.getExpiryDate()))) {
            throw new com.example.hms.exception.FieldBusinessException("lotNumber", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.identity.immutable"));
        }
        // QA D405 (D82, D341 did not hold): the lot edit set any count, above
        // what was received, with no reason and no witness even for a
        // controlled substance. A count now moves only through a stock
        // transaction (ADJUSTMENT), which requires a reason, a witness for a
        // controlled substance, and stays within what the lot received. The
        // count the lot already has is accepted, so a form that echoes it
        // back still saves its other fields.
        if (dto.getRemainingQuantity() != null
                && (remainingBefore == null || dto.getRemainingQuantity().compareTo(remainingBefore) != 0)) {
            throw new com.example.hms.exception.FieldBusinessException("remainingQuantity", 
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.useAdjustment"));
        }
        stockLotMapper.updateEntity(lot, dto);
        StockLot saved = stockLotRepository.save(lot);
        return stockLotMapper.toResponseDTO(saved);
    }

    // ── Reorder alerts ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<InventoryItemResponseDTO> getItemsBelowReorderThreshold(UUID pharmacyId) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        return inventoryItemRepository.findBelowReorderThreshold(pharmacyId).stream()
                .map(inventoryItemMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryItemResponseDTO> getItemsBelowReorderThresholdByHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return List.of();
        }
        return inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId).stream()
                .map(inventoryItemMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional
    public void triggerReorderAlerts() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return;
        }
        List<InventoryItem> lowStock = inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId);
        // Delivery resolves the pharmacy team's live hospital assignments, independently of the caller.
        // The audit row is read by everyone, each in their own language
        // (QA D894): it is written in English, the shape the portal's
        // pattern reads, whatever the caller's language.
        Locale locale = LocaleContextHolder.getLocale();
        String unknownForAudit = messageSource.getMessage("inventory.lowStock.unknown", null, Locale.ENGLISH);
        for (InventoryItem item : lowStock) {
            boolean hasMedication = item.getMedicationCatalogItem() != null;
            boolean hasPharmacy = item.getPharmacy() != null;
            String onHand = String.valueOf(item.getQuantityOnHand());
            String threshold = String.valueOf(item.getReorderThreshold());
            Object[] auditArgs = {hasMedication ? item.getMedicationCatalogItem().getNameFr() : unknownForAudit,
                hasPharmacy ? item.getPharmacy().getName() : unknownForAudit, onHand, threshold};

            pushReorderAlert(item);

            logAudit(AuditEventType.STOCK_REORDER_ALERT,
                messageSource.getMessage("inventory.lowStock.notification", auditArgs, Locale.ENGLISH),
                item.getId().toString());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /** One item's failed delivery must not stop the remaining items. */
    private void pushReorderAlert(InventoryItem item) {
        try {
            lowStockAlertDelivery.deliver(item.getId());
        } catch (Exception e) {
            log.warn("Failed to send reorder alert for item {}: {}", item.getId(), e.getMessage());
        }
    }

    private Pharmacy resolvePharmacy(UUID pharmacyId) {
        return pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacyId));
    }

    private MedicationCatalogItem resolveMedication(UUID medicationId) {
        return medicationCatalogItemRepository.findById(medicationId)
                .orElseThrow(() -> new ResourceNotFoundException("medicationCatalogItem.notFound", medicationId));
    }

    private InventoryItem resolveInventoryItem(UUID id) {
        return inventoryItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("inventoryItem.notFound", id));
    }

    private StockLot resolveStockLot(UUID id) {
        return stockLotRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("stockLot.notFound", id));
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("stock.user.unknown");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private void enforceHospitalScope(Pharmacy pharmacy) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && pharmacy.getHospital() != null
                && !pharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("pharmacy.notFound", pharmacy.getId());
        }
    }

    private void recordStockTransaction(InventoryItem item, StockLot lot,
                                        StockTransactionType type, BigDecimal quantity, String reason,
                                        User witness) {
        recordStockTransaction(item, lot, type, quantity, reason, witness, null);
    }

    private void recordStockTransaction(InventoryItem item, StockLot lot,
                                        StockTransactionType type, BigDecimal quantity, String reason,
                                        User witness, StockLotRequestDTO receipt) {
        User performer = resolveCurrentUser();
        StockTransaction tx = StockTransaction.builder()
                .inventoryItem(item)
                .stockLot(lot)
                .transactionType(type)
                .quantity(quantity)
                .reason(reason)
                .performedByUser(performer)
                .witnessedByUser(witness)
                .build();
        if (receipt != null) {
            tx.setLotNumber(receipt.getLotNumber());
            tx.setExpiryDate(receipt.getExpiryDate());
            tx.setSupplier(receipt.getSupplier());
            tx.setUnitCost(receipt.getUnitCost());
            tx.setReceiptDate(receipt.getReceivedDate());
            tx.setReceiptNotes(receipt.getNotes());
        }
        stockTransactionRepository.save(tx);
    }

    /** QA D4197: who deactivated an item, what it still held, and why, on the audit trail. */
    private void logDeactivation(InventoryItem item, BigDecimal onHand, String reason) {
        logAudit(AuditEventType.CONFIGURATION_CHANGED,
            "Inventory item deactivated with " + onHand.stripTrailingZeros().toPlainString() + " on hand"
                + (reason.isEmpty() ? "" : "; reason: " + reason),
            item.getId() == null ? null : item.getId().toString());
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    // QA D2924 (r66): the acting hospital anchors the row for the hospital admin's audit log.
                    .hospitalId(roleValidator.getCurrentHospitalId())
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType("INVENTORY")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }

    private void refreshIfManaged(Object entity) {
        if (entityManager != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
    }
}
