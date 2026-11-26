package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StockCountConfirmationGuardTest {
    @Test
    void observationsNeverMoveStockAndOnlyExplicitConfirmedAdjustmentsUseTheExistingWorkflow() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        String source = Files.readString(root.resolve("service/pharmacy/StockCountService.java"));
        String record = body(source, "public StockCountResponseDTO record(");
        assertThat(record).doesNotContain("movements.", "StockTransactionType.", "incrementOnHand", "decrementOnHand");
        assertThat(record).contains("ON CONFLICT (hospital_id, client_request_id) DO NOTHING", "requireCountedUnits(");
        String confirm = body(source, "public StockCountResponseDTO confirm(");
        assertThat(confirm.indexOf("!confirmation.confirmed()"))
            .isGreaterThanOrEqualTo(0).isLessThan(confirm.indexOf("movements.recordTransaction("));
        assertThat(confirm).contains("counts.findLocked(id, hospitalId)", "count.getAppliedAt() != null",
            "stockCount.changedSinceCount", ".witnessedBy(confirmation.witnessedBy())", ".referenceId(count.getId())");
        assertThat(source).doesNotContain("items.save(", "lots.save(", "UPDATE clinical.inventory_items", "UPDATE clinical.stock_lots");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String candidate = SourceScan.stripComments(Files.readString(path));
                if (candidate.contains("INSERT INTO clinical.pharmacy_stock_counts")) {
                    assertThat(root.relativize(path).toString().replace('\\', '/')).isEqualTo("service/pharmacy/StockCountService.java");
                }
            }
        }
    }

    private static String body(String source, String signature) {
        int open = source.indexOf('{', source.indexOf(signature));
        return source.substring(open, SourceScan.closingBrace(source, open));
    }
    @Test void allCountConfirmationsGoThroughTheTwoPersonAndProvenanceGate() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(path));
                if (source.contains(".setSamePersonConfirmation(")) {
                    assertThat(root.relativize(path).toString().replace('\\', '/')).isEqualTo("service/pharmacy/StockCountService.java");
                }
            }
        }
        String service = Files.readString(root.resolve("service/pharmacy/StockCountService.java"));
        assertThat(service).contains("findLiveUsersAtHospitalWithRoleCodes(hospitalId,", "stockCount.otherConfirmerRequired", "count.setSamePersonConfirmation(samePerson)");
        assertThat(service).contains("CONFIRMER_ROLE_CODES = WRITERS.stream()", "CONFIRMER_ROLE_CODES).stream()")
            .doesNotContain("LowStockAlertDelivery.RECIPIENT_ROLES");
        assertThat(service.indexOf("stockCount.otherConfirmerRequired")).isLessThan(service.indexOf("movements.recordTransaction("));
    }
}
