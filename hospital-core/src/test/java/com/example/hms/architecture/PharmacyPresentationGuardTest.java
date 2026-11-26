package com.example.hms.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PharmacyPresentationGuardTest {
    @Test
    void manualAndScheduledAlertsUseTheSameRecipientScopedTransactionalClaim() throws Exception {
        Path services = Path.of("src/main/java/com/example/hms/service");
        String delivery = Files.readString(services.resolve("pharmacy/LowStockAlertDelivery.java"));
        assertThat(delivery).contains("TransactionDefinition.PROPAGATION_REQUIRES_NEW", "findLiveUsersAtHospitalWithRoleCodes(hospitalId, RECIPIENT_ROLES)",
            "ON CONFLICT DO NOTHING", "if (claimed == 0) return 0", "transaction.execute(status -> deliverOne(itemId, recipient))", "catch (RuntimeException failure)", "NotificationLocalizer.drugName(", "items.findForAlertDelivery(itemId)");
        assertThat(delivery.indexOf("INSERT INTO clinical.low_stock_alert_receipts"))
            .isLessThan(delivery.indexOf("notifications.createNotification("));
        assertThat(Files.readString(services.resolve("pharmacy/InventoryServiceImpl.java")))
            .contains("lowStockAlertDelivery.deliver(item.getId())");
        String scheduler = Files.readString(services.resolve("pharmacy/LowStockAlertScheduler.java"));
        assertThat(scheduler).contains("@SchedulerLock(", "delivery.deliver(itemId)", "findLowStockIdsAcrossActiveFacilities()");
        try (var paths = Files.walk(services)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path));
                if (source.contains("\"REORDER_ALERT\"")) {
                    assertThat(services.relativize(path).toString().replace('\\', '/'))
                        .as("every stock alert sender must use the durable claim").isEqualTo("pharmacy/LowStockAlertDelivery.java");
                }
            }
        }
    }

    @Test
    void externalLotReceiptLocksTheItemAndRefusesDuplicatesBeforeCreatingRows() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        String service = Files.readString(root.resolve("service/pharmacy/InventoryServiceImpl.java"));
        int start = service.indexOf("public StockLotResponseDTO receiveStock(");
        int open = service.indexOf('{', start);
        String receipt = service.substring(open, com.example.hms.testsupport.SourceScan.closingBrace(service, open));
        assertThat(receipt.indexOf("inventoryItemRepository.lockById(item.getId())"))
            .isGreaterThanOrEqualTo(0).isLessThan(receipt.indexOf("stockLotRepository.findSameLot("));
        assertThat(receipt.indexOf("pharmacy.inventory.lot.duplicate"))
            .isGreaterThanOrEqualTo(0).isLessThan(receipt.indexOf("stockLotMapper.toEntity("));
        assertThat(receipt).contains("matching.size() > 1", "stockLotRepository.addReceipt(", "requireConsistentLotExpiry(item, dto)");
        assertThat(receipt).contains("StockLotBalance.requireReconciled(item, stockLotRepository.sumRemainingForItem(item.getId()))");
        String repository = Files.readString(root.resolve("repository/pharmacy/StockLotRepository.java"));
        assertThat(repository).contains("UPPER(TRIM(s.lotNumber)) = UPPER(TRIM(CAST(:lotNumber AS string)))");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path));
                if (source.contains("StockLot.builder(") || source.contains("new StockLot(")) {
                    // Transfer booking reuses a destination lot under its item lock; it is an internal paired movement.
                    assertThat(root.relativize(path).toString().replace('\\', '/'))
                        .isIn("mapper/pharmacy/StockLotMapper.java", "service/pharmacy/StockTransferBooking.java");
                }
            }
        }
    }

    @Test
    void pharmacyTemplatesNeverAppendAnEnglishDaysLabelToAValue() throws Exception {
        Path pharmacy = Path.of("../hospital-portal/src/app/pharmacy");
        assertThat(Files.readString(pharmacy.resolve("inventory-dashboard.html")))
            .contains("expiryDaysLabelKey(getDaysUntilExpiry(lot.expiryDate))");
        assertThat(Files.readString(pharmacy.resolve("inventory-dashboard.ts")))
            .contains("days === 1 ? 'PHARMACY.DAY_REMAINING_VALUE' : 'PHARMACY.DAYS_REMAINING_VALUE'");
        try (var paths = Files.walk(pharmacy)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".html")).toList()) {
                assertThat(Files.readString(path)).as(path.toString())
                    .doesNotContainPattern("\\}\\}\\s+days\\b");
            }
        }
    }

    @Test
    void everyCounterSaleWriterChecksTheMobileMoneyReferenceBeforeWriting() throws Exception {
        Path services = Path.of("src/main/java/com/example/hms/service");
        int writers = 0;
        try (var paths = Files.walk(services)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (!source.contains("implements PharmacySaleService")) continue;
                writers++;
                assertThat(source).as(path.toString()).contains(
                    ".require(!mobileMoney || org.springframework.util.StringUtils.hasText(dto.getReferenceNumber())",
                    "\"referenceNumber\", \"pharmacy.payment.mobileMoneyReferenceRequired\"");
                assertThat(source.indexOf("pharmacy.payment.mobileMoneyReferenceRequired"))
                    .isLessThan(source.indexOf("saleRepository.save("));
            }
        }
        assertThat(writers).isPositive();
        for (String locale : new String[]{"", "_en", "_fr", "_es"}) {
            Properties messages = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/i18n/messages-pharmacy" + locale + ".properties"))) {
                messages.load(reader);
            }
            assertThat(messages.getProperty("pharmacy.inventory.price.whole")).isNotBlank();
        }
    }
    @Test
    void allCountSnapshotsUseDatabaseMaintainedRevisionsAcrossEveryStockWriter() throws Exception {
        Path root = Path.of("src/main");
        String migration = Files.readString(root.resolve("resources/db/migration/V399__stock_count_revisions.sql"));
        for (String table : new String[]{"inventory_items", "stock_lots"}) {
            assertThat(migration).contains("ALTER TABLE clinical." + table + " ADD COLUMN IF NOT EXISTS stock_revision",
                "BEFORE UPDATE ON clinical." + table);
        }
        assertThat(migration).contains("NEW.stock_revision := OLD.stock_revision + 1", "IS DISTINCT FROM ROW",
            "NEW.quantity_on_hand", "NEW.unit", "NEW.remaining_quantity", "book_item_revision", "book_lot_revision");
        for (String entity : new String[]{"InventoryItem", "StockLot"}) {
            assertThat(Files.readString(root.resolve("java/com/example/hms/model/pharmacy/" + entity + ".java")))
                .contains("org.hibernate.generator.EventType.INSERT", "org.hibernate.generator.EventType.UPDATE", "insertable = false, updatable = false", "Long stockRevision");
        }
        String service = Files.readString(root.resolve("java/com/example/hms/service/pharmacy/StockCountService.java"));
        assertThat(service).contains("sameRevision(item, lot, request.expectedItemRevision(), request.expectedLotRevision())",
            "sameRevision(item, lot, count.getItemRevision(), count.getLotRevision())",
            "book_item_revision, book_lot_revision", "lockStock(item, lot)");
        assertThat(Files.readString(root.resolve("resources/db/migration/changelog.xml")))
            .contains("V399__stock_count_revisions.sql");
    }

}
