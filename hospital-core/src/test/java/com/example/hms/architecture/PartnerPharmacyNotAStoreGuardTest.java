package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4182 (r81): a partner pharmacy registered for the partner hand-off was offered in every store picker, took a
 * transfer of hospital stock, got an inventory item and raised a low-stock alert. The class: a path that resolves a
 * pharmacy of the hospital to hold, move, sell or list stock without asking whether it is one of the hospital's own
 * stores.
 *
 * <p>Every class under {@code service/pharmacy} that looks a pharmacy up says, here, how it keeps a partner out:
 * through {@code PharmacyStores}, through the dispensary check of the fill, or because it only reads rows that can
 * exist in an own store alone. A new class that looks a pharmacy up fails this test until it is added with its
 * answer. The portal's half is {@code scripts/lib/r81-pharmacy-dispensing.test.mjs}.
 */
@DisplayName("QA D4182: a partner pharmacy is not one of the hospital's stores")
class PartnerPharmacyNotAStoreGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path PHARMACY = MAIN.resolve("service/pharmacy");

    private static final Pattern PHARMACY_LOOKUP = Pattern.compile(
        "\\b(pharmacyRepository|pharmacies)\\s*\\.\\s*(find|exists|get)\\w*\\(");

    /** The marker each class must contain, proving how a partner pharmacy is kept out (or why it need not be). */
    private static final Map<String, String> HOW = new TreeMap<>(Map.ofEntries(
        // Writers of stock or money at a pharmacy: refused through the one rule.
        Map.entry("InventoryServiceImpl.java", "PharmacyStores.requireOwnStore(pharmacy, \"pharmacyId\")"),
        Map.entry("StockTransferBooking.java", "PharmacyStores.requireOwnStore(locked, \"referenceId\")"),
        Map.entry("PharmacySaleServiceImpl.java", "PharmacyStores.requireOwnStore(pharmacy, \"pharmacyId\")"),
        Map.entry("PharmacyReconciliationService.java", ".filter(PharmacyStores::isOwnStore)"),
        // The fill has had its own dispensary check since G12.
        Map.entry("DispenseServiceImpl.java", "requireDispensary(pharmacy)"),
        // The partner hand-off itself: it must reach partners, and counts own stock by type.
        Map.entry("StockOutRoutingServiceImpl.java", "getPharmacyType() == PharmacyType.HOSPITAL_DISPENSARY"),
        // Readers of rows that only an own store can hold (no item, lot or movement is created in a partner):
        // scope checks only.
        Map.entry("StockTransactionServiceImpl.java", "enforceHospitalScope(pharmacy)"),
        Map.entry("ControlledDrugRegisterService.java", "pharmacyRepository.findById(pharmacyId)"),
        Map.entry("QuarantineDestructionService.java", "pharmacyRepository.findById(pharmacyId)"),
        Map.entry("StockCountService.java", "pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId)"),
        Map.entry("PharmacyConsumptionService.java", "pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId)")));

    @Test
    @DisplayName("every pharmacy lookup under service/pharmacy says how a partner is kept out")
    void everyPharmacyLookupIsAccountedFor() throws IOException {
        List<String> unaccounted = new ArrayList<>();
        try (var paths = Files.walk(PHARMACY)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = read(path);
                if (!PHARMACY_LOOKUP.matcher(source).find()) {
                    continue;
                }
                String marker = HOW.get(path.getFileName().toString());
                if (marker == null) {
                    unaccounted.add(path.getFileName() + " looks a pharmacy up and is not listed in this guard");
                } else if (!source.contains(marker)) {
                    unaccounted.add(path.getFileName() + " no longer contains: " + marker);
                }
            }
        }
        assertThat(unaccounted)
            .as("a class that holds, moves, sells or lists stock at a pharmacy must call "
                + "PharmacyStores.requireOwnStore / isOwnStore, then be listed here")
            .isEmpty();
    }

    @Test
    @DisplayName("stock enters a pharmacy through three doors, each behind the own-store rule")
    void theThreeDoorsOfStockAreGuarded() throws IOException {
        String inventory = read(PHARMACY.resolve("InventoryServiceImpl.java"));
        assertThat(inventory)
            .as("a new inventory item").contains("PharmacyStores.requireOwnStore(pharmacy, \"pharmacyId\")")
            .as("a goods receipt").contains("PharmacyStores.requireOwnStore(item.getPharmacy(), \"inventoryItemId\")");
        assertThat(read(PHARMACY.resolve("StockTransferBooking.java")))
            .as("a transfer's destination").contains("PharmacyStores.requireOwnStore(locked, \"referenceId\")");
    }

    @Test
    @DisplayName("the lists that name stores leave partners out in the query itself")
    void theStoreListsFilterByType() throws IOException {
        String type = "pharmacyType = com.example.hms.enums.PharmacyType.HOSPITAL_DISPENSARY";
        String pharmacies = read(MAIN.resolve("repository/pharmacy/PharmacyRepository.java"));
        String stores = pharmacies.substring(pharmacies.indexOf("QA D4182"), pharmacies.indexOf("findActiveStoresPage("));
        assertThat(stores).as("the store pickers' list, and its count").contains("p." + type);
        assertThat(stores.split(Pattern.quote("p." + type), -1)).hasSize(3);
        String items = read(MAIN.resolve("repository/pharmacy/InventoryItemRepository.java"));
        for (String query : List.of("findBelowReorderThreshold(", "findBelowReorderThresholdByHospital(")) {
            int end = items.indexOf(query);
            String jpql = items.substring(items.lastIndexOf("@Query(", end), end);
            assertThat(jpql).as("low-stock alerts are about the hospital's own stores: " + query)
                .contains("i.pharmacy." + type);
        }
        assertThat(read(MAIN.resolve("controller/PharmacyRegistryController.java")))
            .contains("boolean storesOnly").contains("pharmacyService.listStores(scope, pageable)");
    }

    /** The file's text with Unix line ends, whatever the checkout wrote. */
    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
