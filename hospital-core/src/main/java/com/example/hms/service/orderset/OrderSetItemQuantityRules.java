package com.example.hms.service.orderset;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * QA D1396 (round 33): a set with a controlled drug could be saved and never
 * applied. The prescription rule (QA D706, D922) refuses a controlled order
 * without the quantity to hand over; the item had no quantity, so every apply
 * was refused, whole, and the author had nothing to fix.
 *
 * <p>An item may now carry {@code quantity} and {@code quantityUnit}
 * ({@link OrderSetItemDispatcher} hands them to the order). A drug this
 * hospital's catalogue marks controlled must state the quantity:
 * {@link #requireValid} refuses it by name at SAVE (every writer, every new
 * version), and again at APPLY for an item saved before this rule, before any
 * order is created. Nothing is ever skipped. The reason names the item's own
 * field ({@code orderItems[2].quantity}). A name several catalogue items answer
 * to, one of them controlled, could never be applied either: the item then names
 * its pick ({@code orderItems[2].medicationCatalogItemId}, the editor's list).
 */
@Component
@RequiredArgsConstructor
public class OrderSetItemQuantityRules {

    /** Item key of the quantity to hand over. */
    public static final String QUANTITY_KEY = "quantity";

    /** Item key of what is counted (ampoules, tablets ...). */
    public static final String QUANTITY_UNIT_KEY = "quantityUnit";

    /** Item key of the catalogue pick. */
    public static final String CATALOG_ITEM_KEY = "medicationCatalogItemId";

    /** The same ceiling as {@code PrescriptionRequestDTO.quantity}. */
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("100000");

    private static final int MAX_QUANTITY_DECIMALS = 2;

    /** "Item {0} ({1}): {2}" - the item's position, its name, the sentence. */
    private static final String ITEM_REFUSED_KEY = "admissionOrderSet.item.prnRefused";

    private static final String REQUIRED_KEY = "admissionOrderSet.item.quantityRequired";

    private static final String PICK_REQUIRED_KEY = "admissionOrderSet.item.pickRequired";

    private static final String NAME_KEY = "medicationName";

    private static final String CODE_KEY = "medicationCode";

    private final MedicationCatalogItemRepository catalogRepository;

    /**
     * Refuses the first MEDICATION item with an invalid quantity, or a
     * controlled one without its quantity.
     *
     * @param items      the items as written or stored
     * @param hospitalId the hospital the set belongs to: its catalogue decides
     */
    public void requireValid(List<Map<String, Object>> items, UUID hospitalId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // One read of the catalogue, and none for a set with no medication.
        boolean asks = items.stream().anyMatch(item -> item != null && isMedication(item));
        List<MedicationCatalogItem> catalogue = asks ? activeItems(hospitalId) : List.of();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (item != null && isMedication(item)) {
                requireValid(i, item, catalogue, hospitalId);
            }
        }
    }

    /**
     * Resolved in the order the order it creates is resolved
     * ({@code PrescriptionServiceImpl.applyCatalogPick}): the pick, the code,
     * then the name. A name that reaches a controlled drug among several items
     * could never be applied, so it is refused here, on the pick.
     */
    private void requireValid(int index, Map<String, Object> item, List<MedicationCatalogItem> catalogue,
                              UUID hospitalId) {
        BigDecimal quantity = quantityOf(item);
        if (quantity == null && isWritten(item.get(QUANTITY_KEY))) {
            throw refused(index, item, QUANTITY_KEY, "prescription.quantity.positive");
        }
        String pick = text(item.get(CATALOG_ITEM_KEY));
        if (pick != null) {
            MedicationCatalogItem picked = pickedItem(pick, hospitalId);
            if (picked == null) {
                throw refused(index, item, CATALOG_ITEM_KEY, "medication.catalog.notfound");
            }
            requireQuantityOf(index, item, quantity, picked.isControlled());
            return;
        }
        String code = text(item.get(CODE_KEY));
        MedicationCatalogItem coded = code == null || hospitalId == null
            ? null : catalogRepository.findByHospitalIdAndCode(hospitalId, code).orElse(null);
        if (coded != null) {
            requireQuantityOf(index, item, quantity, coded.isControlled());
            return;
        }
        String name = text(item.get(NAME_KEY));
        List<MedicationCatalogItem> named = name == null ? List.of() : catalogue.stream()
            .filter(drug -> DispenseVerificationService.namesCatalogItem(name, drug))
            .toList();
        boolean controlled = named.stream().anyMatch(MedicationCatalogItem::isControlled);
        if (controlled && named.size() > 1) {
            throw refused(index, item, CATALOG_ITEM_KEY, PICK_REQUIRED_KEY);
        }
        requireQuantityOf(index, item, quantity, controlled);
    }

    private static void requireQuantityOf(int index, Map<String, Object> item, BigDecimal quantity, boolean controlled) {
        if (controlled && quantity == null) {
            throw refused(index, item, QUANTITY_KEY, REQUIRED_KEY);
        }
    }

    private MedicationCatalogItem pickedItem(String pick, UUID hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        try {
            return catalogRepository.findByIdAndHospital_Id(UUID.fromString(pick), hospitalId).orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The item's quantity when it is a positive number the order may carry, else {@code null}. */
    public static BigDecimal quantityOf(Map<String, Object> item) {
        Object value = item.get(QUANTITY_KEY);
        BigDecimal quantity = null;
        try {
            if (value instanceof Number || value instanceof String) {
                quantity = new BigDecimal(value.toString().strip());
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(MAX_QUANTITY) > 0
            || quantity.stripTrailingZeros().scale() > MAX_QUANTITY_DECIMALS) {
            return null;
        }
        return quantity;
    }

    private static boolean isWritten(Object value) {
        return value != null && !(value instanceof String s && s.isBlank());
    }

    private List<MedicationCatalogItem> activeItems(UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        return catalogRepository.findByHospital_IdAndActiveTrue(hospitalId, Pageable.unpaged()).getContent();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private static boolean isMedication(Map<String, Object> item) {
        return item.get("orderType") instanceof String type
            && OrderSetItemDispatcher.TYPE_MEDICATION.equals(type.strip().toUpperCase(Locale.ROOT));
    }

    private static FieldBusinessException refused(int index, Map<String, Object> item, String field, String key) {
        String name = text(item.get(NAME_KEY));
        return new FieldBusinessException(OrderSetItemPrnRules.ITEMS_FIELD + "[" + index + "]." + field,
            ITEM_REFUSED_KEY, index + 1, name == null ? "-" : name, MessageUtil.resolve(key));
    }
}
