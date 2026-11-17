package com.example.hms.service.pharmacy;

import com.example.hms.repository.MedicationCatalogItemRepository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * QA D1314: the catalogue's controlled flag for many orders, read in one
 * statement. The rule is {@link PrescriptionControlledResolver#catalogControlled}:
 * the item picked decides, else the code, always at the order's own hospital.
 */
public final class CatalogControlledIndex {

    /** An order's keys into the catalogue. */
    public record Order(UUID hospitalId, UUID catalogItemId, String medicationCode) {
    }

    private record Item(UUID hospitalId, UUID itemId) {
    }

    private record Code(UUID hospitalId, String code) {
    }

    /** No catalogue line is controlled. */
    public static final CatalogControlledIndex EMPTY = new CatalogControlledIndex(Set.of(), Set.of());

    private final Set<Item> items;
    private final Set<Code> codes;

    private CatalogControlledIndex(Set<Item> items, Set<Code> codes) {
        this.items = items;
        this.codes = codes;
    }

    /** The controlled lines among those {@code orders} name. No statement when they name none. */
    public static CatalogControlledIndex read(MedicationCatalogItemRepository catalog, Collection<Order> orders) {
        Set<UUID> hospitalIds = new HashSet<>();
        Set<UUID> itemIds = new HashSet<>();
        Set<String> wantedCodes = new HashSet<>();
        for (Order order : orders) {
            if (order.hospitalId() == null) {
                continue;
            }
            if (order.catalogItemId() != null) {
                hospitalIds.add(order.hospitalId());
                itemIds.add(order.catalogItemId());
            } else if (order.medicationCode() != null && !order.medicationCode().isBlank()) {
                hospitalIds.add(order.hospitalId());
                wantedCodes.add(order.medicationCode().trim());
            }
        }
        if (hospitalIds.isEmpty()) {
            return EMPTY;
        }
        Set<Item> items = new HashSet<>();
        Set<Code> codes = new HashSet<>();
        for (Object[] row : catalog.findControlledAmong(hospitalIds, itemIds, wantedCodes)) {
            UUID hospitalId = (UUID) row[1];
            items.add(new Item(hospitalId, (UUID) row[0]));
            if (row[2] != null) {
                codes.add(new Code(hospitalId, (String) row[2]));
            }
        }
        return new CatalogControlledIndex(items, codes);
    }

    /** {@link PrescriptionControlledResolver#catalogControlled}, from what was read. */
    public boolean controlled(UUID hospitalId, UUID catalogItemId, String medicationCode) {
        if (hospitalId == null) {
            return false;
        }
        if (catalogItemId != null) {
            return items.contains(new Item(hospitalId, catalogItemId));
        }
        if (medicationCode == null || medicationCode.isBlank()) {
            return false;
        }
        return codes.contains(new Code(hospitalId, medicationCode.trim()));
    }
}
