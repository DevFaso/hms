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
 * field ({@code orderItems[2].quantity}).
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
        // One read of the catalogue, and none for a set nothing can be asked of.
        boolean asks = items.stream().anyMatch(item -> item != null && isMedication(item) && quantityOf(item) == null);
        List<MedicationCatalogItem> controlled = asks ? controlledItems(hospitalId) : List.of();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String refusal = item != null && isMedication(item) ? refusalOf(item, controlled) : null;
            if (refusal != null) {
                throw refused(i, item, refusal);
            }
        }
    }

    /** The message key of what is wrong with a MEDICATION item's quantity, or {@code null}. */
    private static String refusalOf(Map<String, Object> item, List<MedicationCatalogItem> controlled) {
        if (quantityOf(item) != null) {
            return null;
        }
        if (isWritten(item.get(QUANTITY_KEY))) {
            return "prescription.quantity.positive";
        }
        return namesControlledDrug(item, controlled) ? REQUIRED_KEY : null;
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

    private List<MedicationCatalogItem> controlledItems(UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        return catalogRepository.findByHospital_IdAndActiveTrue(hospitalId, Pageable.unpaged()).getContent().stream()
            .filter(MedicationCatalogItem::isControlled)
            .toList();
    }

    /** By the item's catalogue pick, its code or its name, as the order it creates is resolved. */
    private static boolean namesControlledDrug(Map<String, Object> item, List<MedicationCatalogItem> controlled) {
        String pick = text(item.get(CATALOG_ITEM_KEY));
        String code = text(item.get("medicationCode"));
        String name = text(item.get("medicationName"));
        return controlled.stream().anyMatch(drug -> names(drug, pick, code, name));
    }

    private static boolean names(MedicationCatalogItem drug, String pick, String code, String name) {
        UUID id = drug.getId();
        return (pick != null && id != null && pick.equalsIgnoreCase(id.toString()))
            || (code != null && code.equalsIgnoreCase(drug.getCode()))
            || (name != null && DispenseVerificationService.namesCatalogItem(name, drug));
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private static boolean isMedication(Map<String, Object> item) {
        return item.get("orderType") instanceof String type
            && OrderSetItemDispatcher.TYPE_MEDICATION.equals(type.strip().toUpperCase(Locale.ROOT));
    }

    private static FieldBusinessException refused(int index, Map<String, Object> item, String key) {
        String name = text(item.get("medicationName"));
        return new FieldBusinessException(OrderSetItemPrnRules.ITEMS_FIELD + "[" + index + "]." + QUANTITY_KEY,
            ITEM_REFUSED_KEY, index + 1, name == null ? "-" : name, MessageUtil.resolve(key));
    }
}
