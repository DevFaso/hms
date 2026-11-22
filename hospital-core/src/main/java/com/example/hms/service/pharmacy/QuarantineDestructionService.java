package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.QuarantineDestructionRequestDTO;
import com.example.hms.payload.dto.pharmacy.QuarantineLineDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3415 (r70): the quarantine of a pharmacy (units set aside by {@link StockQuarantine}) and their destruction.
 * There was no quarantine or destruction movement for controlled drugs: a broken ampoule was a generic « Ajustement
 * −1 » and a returned one went back on sale. A destruction takes units out of the quarantine only (the saleable stock
 * already lost them), never more than it holds, recorded by the signed-in user with a reason, and in front of a
 * witness (not the performer, holding a stock-witness role at the hospital) for a controlled drug. It shows in the
 * controlled register.
 */
@Service
@RequiredArgsConstructor
public class QuarantineDestructionService {

    static final Set<StockTransactionType> QUARANTINE_TYPES =
            EnumSet.of(StockTransactionType.QUARANTINE, StockTransactionType.DESTRUCTION);
    /** The longest reason kept (the column holds 500; the prefix takes the rest). */
    static final int MAX_REASON = 300;
    static final String REASON_PREFIX = "Destroyed: ";
    private static final String FIELD_QUANTITY = "quantity";

    private final PharmacyRepository pharmacyRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockLotRepository stockLotRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final StockTransactionMapper stockTransactionMapper;
    private final ControlledStockWitness controlledStockWitness;
    private final PharmacyServiceSupport support;
    private final RoleValidator roleValidator;

    /** What the pharmacy holds in quarantine, per item and lot, by name; empty lines are left out. */
    @Transactional(readOnly = true)
    public List<QuarantineLineDTO> list(UUID pharmacyId) {
        Pharmacy pharmacy = pharmacyInScope(pharmacyId);
        Map<List<UUID>, BigDecimal> held = new LinkedHashMap<>();
        List<Object[]> rows = stockTransactionRepository.sumByItemLotAndTypeForPharmacy(pharmacy.getId(),
                QUARANTINE_TYPES);
        for (Object[] row : rows == null ? List.<Object[]>of() : rows) {
            if (row != null && row.length == 4 && row[0] instanceof UUID itemId
                    && row[2] instanceof StockTransactionType type && row[3] instanceof BigDecimal total) {
                UUID lotId = row[1] instanceof UUID id ? id : null;
                held.merge(java.util.Arrays.asList(itemId, lotId), quarantineSigned(type, total), BigDecimal::add);
            }
        }
        Map<UUID, InventoryItem> items = new HashMap<>();
        inventoryItemRepository.findAllWithCatalogItemByPharmacyId(pharmacy.getId())
                .forEach(item -> items.put(item.getId(), item));
        List<QuarantineLineDTO> out = new ArrayList<>();
        held.forEach((key, quantity) -> {
            InventoryItem item = items.get(key.get(0));
            if (item == null || quantity.signum() <= 0) {
                return;
            }
            StockLot lot = key.get(1) != null ? stockLotRepository.findById(key.get(1)).orElse(null) : null;
            out.add(new QuarantineLineDTO(item.getId(), ControlledDrugRegisterService.drugName(item), item.getUnit(),
                    ControlledStockWitness.isControlled(item), lot != null ? lot.getId() : null,
                    lot != null ? lot.getLotNumber() : null, quantity));
        });
        out.sort(Comparator.comparing((QuarantineLineDTO l) -> Objects.toString(l.medicationName(), ""),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(l -> Objects.toString(l.lotNumber(), "")));
        return out;
    }

    /** Destroys quarantined units: one DESTRUCTION row, witnessed for a controlled drug. */
    @Transactional
    public StockTransactionResponseDTO destroy(QuarantineDestructionRequestDTO body) {
        QuarantineDestructionRequestDTO dto = body != null ? body : new QuarantineDestructionRequestDTO();
        String reason = dto.getReason() != null ? dto.getReason().trim() : "";
        RequiredFields.check()
                .requireValue(dto.getInventoryItemId(), "inventoryItemId", "pharmacy.quarantine.itemRequired")
                .require(dto.getQuantity() != null && dto.getQuantity().signum() > 0, FIELD_QUANTITY,
                        "pharmacy.quarantine.quantityPositive")
                .require(!reason.isEmpty(), "reason", "pharmacy.quarantine.reasonRequired")
                .orThrow();
        if (reason.length() > MAX_REASON) {
            throw new FieldBusinessException("reason", "pharmacy.quarantine.reasonTooLong", String.valueOf(MAX_REASON));
        }
        InventoryItem scoped = inventoryItemRepository.findById(dto.getInventoryItemId())
                .orElseThrow(() -> new ResourceNotFoundException("inventoryItem.notFound", dto.getInventoryItemId()));
        pharmacyInScope(scoped.getPharmacy() != null ? scoped.getPharmacy().getId() : null);
        // The item row locked: two destructions of the same quarantine cannot both pass the balance check.
        InventoryItem item = inventoryItemRepository.lockById(scoped.getId())
                .orElseThrow(() -> new ResourceNotFoundException("inventoryItem.notFound", scoped.getId()));
        StockLot lot = null;
        if (dto.getStockLotId() != null) {
            lot = stockLotRepository.findById(dto.getStockLotId())
                    .filter(l -> l.getInventoryItem() != null && item.getId().equals(l.getInventoryItem().getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("stockLot.notFound", dto.getStockLotId()));
        }
        WholeUnitQuantity.require(item, dto.getQuantity(), WholeUnitQuantity.DEFAULT_KEY);
        BigDecimal held = held(item, lot);
        if (dto.getQuantity().compareTo(held) > 0) {
            throw new FieldBusinessException(FIELD_QUANTITY, "pharmacy.quarantine.aboveHeld", held.stripTrailingZeros()
                    .toPlainString());
        }
        User performer = support.resolveCurrentUser();
        User witness = controlledStockWitness.resolve(dto.getWitnessedBy(), item, performer);
        StockTransaction saved = stockTransactionRepository.save(StockTransaction.builder()
                .inventoryItem(item)
                .stockLot(lot)
                .transactionType(StockTransactionType.DESTRUCTION)
                .quantity(dto.getQuantity())
                .reason(REASON_PREFIX + reason)
                .performedByUser(performer)
                .witnessedByUser(witness)
                .build());
        support.logAudit(AuditEventType.STOCK_ADJUSTMENT, "Quarantined stock destroyed: " + dto.getQuantity()
                + " units", String.valueOf(saved.getId()), "STOCK_TRANSACTION");
        return stockTransactionMapper.toResponseDTO(saved);
    }

    /** What the quarantine holds for this item and lot (lot-less units when {@code lot} is null). */
    private BigDecimal held(InventoryItem item, StockLot lot) {
        BigDecimal in;
        BigDecimal out;
        if (lot != null) {
            in = stockTransactionRepository.sumQuantityForLot(lot.getId(), EnumSet.of(StockTransactionType.QUARANTINE));
            out = stockTransactionRepository.sumQuantityForLot(lot.getId(),
                    EnumSet.of(StockTransactionType.DESTRUCTION));
        } else {
            in = stockTransactionRepository.sumQuantityForItemWithoutLot(item.getId(),
                    EnumSet.of(StockTransactionType.QUARANTINE));
            out = stockTransactionRepository.sumQuantityForItemWithoutLot(item.getId(),
                    EnumSet.of(StockTransactionType.DESTRUCTION));
        }
        return nz(in).subtract(nz(out)).max(BigDecimal.ZERO);
    }

    /** What a movement does to the quarantine: a QUARANTINE adds, a DESTRUCTION removes, nothing else counts. */
    static BigDecimal quarantineSigned(StockTransactionType type, BigDecimal quantity) {
        BigDecimal q = nz(quantity);
        if (type == StockTransactionType.QUARANTINE) {
            return q;
        }
        return type == StockTransactionType.DESTRUCTION ? q.negate() : BigDecimal.ZERO;
    }

    private Pharmacy pharmacyInScope(UUID pharmacyId) {
        if (pharmacyId == null) {
            throw new ResourceNotFoundException("pharmacy.notFound", (Object) null);
        }
        Pharmacy pharmacy = pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacyId));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (pharmacy.getHospital() == null
                || !hospitalId.equals(pharmacy.getHospital().getId()))) {
            throw new ResourceNotFoundException("pharmacy.notFound", pharmacyId);
        }
        return pharmacy;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
