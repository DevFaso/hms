package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockCount;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.*;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.*;
import com.example.hms.utility.DrugDisplayNames;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D3951: recording is observational; only a separate confirmation enters the existing adjustment workflow. */
@Service
@RequiredArgsConstructor
public class StockCountService {
    private static final Set<String> WRITERS = Set.of("PHARMACIST", "INVENTORY_CLERK", "STORE_MANAGER", "HOSPITAL_ADMIN");
    private final StockCountRepository counts;
    private final InventoryItemRepository items;
    private final StockLotRepository lots;
    private final PharmacyRepository pharmacies;
    private final UserRepository users;
    private final StockTransactionService movements;
    private final RoleValidator roles;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public StockCountResponseDTO record(StockCountRequestDTO request) {
        UUID hospitalId = requireScope(true);
        validate(request);
        StockCount previous = counts.findByHospitalIdAndClientRequestId(hospitalId, request.clientRequestId()).orElse(null);
        if (previous != null) return sameRequest(previous, request);
        InventoryItem item = ownedItem(request.inventoryItemId(), hospitalId);
        StockLot lot = ownedLot(request.stockLotId(), item);
        lockStock(item, lot);
        BigDecimal book = lot == null ? item.getQuantityOnHand() : lot.getRemainingQuantity();
        if (book.compareTo(request.expectedBookQuantity()) != 0 || !Objects.equals(item.getUnit(), request.expectedUnit())) {
            throw new BusinessException("stockCount.changedSinceCount");
        }
        requireCountedUnits(item, request.countedQuantity());
        User actor = actor();
        // The unique request is hospital-scoped. Concurrent retries never create a second observation.
        jdbc.update("""
            INSERT INTO clinical.pharmacy_stock_counts
                (id, hospital_id, inventory_item_id, stock_lot_id, client_request_id, book_quantity,
                 counted_quantity, variance, unit, reason, medication_name_fr, medication_name_en, lot_number,
                 recorded_by_user_id, recorded_by_name, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (hospital_id, client_request_id) DO NOTHING
            """, UUID.randomUUID(), hospitalId, item.getId(), lot == null ? null : lot.getId(), request.clientRequestId(),
            book, request.countedQuantity(), request.countedQuantity().subtract(book), item.getUnit(), request.reason().trim(),
            DrugDisplayNames.forReader(item.getMedicationCatalogItem(), Locale.FRENCH),
            DrugDisplayNames.forReader(item.getMedicationCatalogItem(), Locale.ENGLISH), lot == null ? null : lot.getLotNumber(),
            actor.getId(), actorName(actor), LocalDateTime.now(clock));
        StockCount saved = counts.findByHospitalIdAndClientRequestId(hospitalId, request.clientRequestId()).orElseThrow();
        return sameRequest(saved, request);
    }

    @Transactional
    public StockCountResponseDTO confirm(UUID id, StockCountConfirmationDTO confirmation) {
        UUID hospitalId = requireScope(true);
        StockCount count = counts.findLocked(id, hospitalId).orElseThrow(() -> notFound(id));
        if (confirmation == null || !confirmation.confirmed()) {
            throw new FieldBusinessException("confirmed", "stockCount.confirmRequired");
        }
        if (count.getAppliedAt() != null) return response(count);
        InventoryItem item = ownedItem(count.getInventoryItem().getId(), hospitalId);
        StockLot lot = ownedLot(count.getStockLot() == null ? null : count.getStockLot().getId(), item);
        lockStock(item, lot);
        BigDecimal current = lot == null ? item.getQuantityOnHand() : lot.getRemainingQuantity();
        if (current.compareTo(count.getBookQuantity()) != 0 || !Objects.equals(item.getUnit(), count.getUnit())) {
            throw new BusinessException("stockCount.changedSinceCount");
        }
        User actor = actor();
        if (count.getVariance().signum() != 0) {
            StockTransactionResponseDTO adjustment = movements.recordTransaction(StockTransactionRequestDTO.builder()
                .inventoryItemId(item.getId()).stockLotId(lot == null ? null : lot.getId())
                .transactionType(StockTransactionType.ADJUSTMENT).quantity(count.getVariance())
                .reason(MessageUtil.resolve("stockCount.adjustmentReason", count.getReason()))
                .referenceId(count.getId()).witnessedBy(confirmation.witnessedBy()).build());
            count.setAdjustmentId(adjustment.getId());
        }
        count.setAppliedAt(LocalDateTime.now(clock));
        count.setAppliedBy(actor);
        count.setAppliedByName(actorName(actor));
        return response(counts.save(count));
    }

    @Transactional(readOnly = true)
    public Page<StockCountResponseDTO> list(UUID pharmacyId, Pageable pageable) {
        UUID hospitalId = requireScope(false);
        pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId).orElseThrow(() -> notFound(pharmacyId));
        return counts.findForPharmacy(hospitalId, pharmacyId, pageable).map(StockCountService::response);
    }

    private UUID requireScope(boolean write) {
        UUID hospitalId = roles.requireActiveHospitalId();
        UUID userId = roles.getCurrentUserId();
        boolean writer = hospitalId != null && userId != null && (roles.isSuperAdminActingAt(hospitalId)
            || WRITERS.stream().anyMatch(role -> roles.hasRole(userId, hospitalId, role)));
        if (!writer && (write || hospitalId == null || userId == null || !roles.canReadPharmacyReconciliation(userId, hospitalId))) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("stockCount.forbidden"));
        }
        return hospitalId;
    }

    UUID readerHospital() {
        return requireScope(false);
    }

    private InventoryItem ownedItem(UUID id, UUID hospitalId) {
        InventoryItem item = items.findById(id).orElseThrow(() -> notFound(id));
        if (item.getPharmacy() == null || item.getPharmacy().getHospital() == null
                || !hospitalId.equals(item.getPharmacy().getHospital().getId())) throw notFound(id);
        return item;
    }

    private StockLot ownedLot(UUID id, InventoryItem item) {
        if (id == null) {
            if (lots.existsByInventoryItemId(item.getId())) throw new FieldBusinessException("stockLotId", "stockCount.lotRequired");
            return null;
        }
        StockLot lot = lots.findById(id).orElseThrow(() -> notFound(id));
        if (lot.getInventoryItem() == null || !item.getId().equals(lot.getInventoryItem().getId())) throw notFound(id);
        return lot;
    }

    private void lockStock(InventoryItem item, StockLot lot) {
        try {
            items.tryLockById(item.getId()).orElseThrow(() -> notFound(item.getId()));
            if (lot != null) {
                lots.tryLockById(lot.getId()).orElseThrow(() -> notFound(lot.getId()));
                entityManager.refresh(lot);
            }
            entityManager.refresh(item);
        } catch (DataAccessException busy) {
            if (!StockRowLocks.isContention(busy)) throw busy;
            FieldBusinessException refusal = new FieldBusinessException("countedQuantity", "stockCount.stockBusy");
            refusal.initCause(busy);
            throw refusal;
        }
    }

    private User actor() {
        UUID id = roles.getCurrentUserId();
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("user.notFound", id));
    }

    private static String actorName(User user) {
        return Stream.of(user.getFirstName(), user.getLastName()).filter(Objects::nonNull)
            .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.joining(" "));
    }

    private static void validate(StockCountRequestDTO request) {
        if (request.inventoryItemId() == null) throw new FieldBusinessException("inventoryItemId", "stockTransaction.inventoryItemId.required");
        if (request.clientRequestId() == null) throw new FieldBusinessException("clientRequestId", "stockCount.requestRequired");
        if (request.expectedBookQuantity() == null) throw new FieldBusinessException("inventoryItemId", "stockCount.startRequired");
        if (request.countedQuantity() == null) throw new FieldBusinessException("countedQuantity", "stockCount.quantityRequired");
        if (request.countedQuantity().signum() < 0) throw new FieldBusinessException("countedQuantity", "stockCount.quantityNonNegative");
        if (request.countedQuantity().scale() > 2 || request.countedQuantity().precision() - request.countedQuantity().scale() > 10)
            throw new FieldBusinessException("countedQuantity", "stockCount.quantityPrecision");
        if (request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > 300)
            throw new FieldBusinessException("reason", "stockCount.reasonRequired");
    }

    private static void requireCountedUnits(InventoryItem item, BigDecimal quantity) {
        try {
            WholeUnitQuantity.require(item, quantity);
        } catch (BusinessException invalid) {
            FieldBusinessException refusal = new FieldBusinessException("countedQuantity", WholeUnitQuantity.DEFAULT_KEY);
            refusal.initCause(invalid);
            throw refusal;
        }
    }

    private static StockCountResponseDTO sameRequest(StockCount count, StockCountRequestDTO request) {
        UUID lotId = count.getStockLot() == null ? null : count.getStockLot().getId();
        if (!count.getInventoryItem().getId().equals(request.inventoryItemId()) || !Objects.equals(lotId, request.stockLotId())
                || count.getBookQuantity().compareTo(request.expectedBookQuantity()) != 0 || !Objects.equals(count.getUnit(), request.expectedUnit())
                || count.getCountedQuantity().compareTo(request.countedQuantity()) != 0 || !count.getReason().equals(request.reason().trim())) {
            throw new BusinessException("stockCount.requestReused");
        }
        return response(count);
    }

    private static StockCountResponseDTO response(StockCount count) {
        String name = "en".equals(com.example.hms.utility.DisplayNumbers.requestLocale().getLanguage())
            ? count.getMedicationNameEn() : count.getMedicationNameFr();
        return new StockCountResponseDTO(count.getId(), count.getInventoryItem().getId(),
            count.getStockLot() == null ? null : count.getStockLot().getId(), name, count.getLotNumber(), count.getUnit(),
            count.getBookQuantity(), count.getCountedQuantity(), count.getVariance(), count.getReason(), count.getRecordedByName(),
            count.getRecordedAt(), count.getAppliedAt(), count.getAppliedByName(), count.getAdjustmentId());
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("stockCount.notFound", id);
    }
}
