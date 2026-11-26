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
        assertThat(delivery).contains("Propagation.REQUIRES_NEW", "findLiveUsersAtHospitalWithRoleCodes(hospitalId, RECIPIENT_ROLES)",
            "ON CONFLICT DO NOTHING", "if (claimed == 1)");
        assertThat(delivery.indexOf("INSERT INTO clinical.low_stock_alert_receipts"))
            .isLessThan(delivery.indexOf("notifications.createNotification("));
        assertThat(Files.readString(services.resolve("pharmacy/InventoryServiceImpl.java")))
            .contains("lowStockAlertDelivery.deliver(item, alertArgs)");
        String scheduler = Files.readString(services.resolve("pharmacy/LowStockAlertScheduler.java"));
        assertThat(scheduler).contains("@SchedulerLock(", "delivery.deliver(item, arguments)");
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
            .isGreaterThanOrEqualTo(0).isLessThan(receipt.indexOf("stockLotRepository.existsLotNumberForItem("));
        assertThat(receipt.indexOf("pharmacy.inventory.lot.duplicate"))
            .isGreaterThanOrEqualTo(0).isLessThan(receipt.indexOf("stockLotMapper.toEntity("));
        assertThat(receipt).doesNotContain("stockLotRepository.addReceipt(");
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
                    "dto.getReferenceNumber() == null || dto.getReferenceNumber().isBlank()",
                    "FieldBusinessException(\"referenceNumber\", \"pharmacy.payment.mobileMoneyReferenceRequired\")");
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
}
