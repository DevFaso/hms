package com.example.hms.architecture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StockBalanceReconciliationGuardTest {
    @Test void reconciliationChecksAllLotCountsUnderLocksBeforeRecordingTheAggregateAdjustment() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/example/hms/service/pharmacy/StockBalanceReconciliationService.java"));
        assertThat(service).contains("roles.requireActiveHospitalId()", "items.lockById(itemId)", "lots.tryLockById(candidate.getId())",
            "entityManager.refresh(lot)", "physicallyCounted.compareTo(lot.getRemainingQuantity())", "expectedItemRevision()",
            "request.expectedLotQuantity()", "witness.resolve(request.witnessedBy(), item, actor)",
            "StockTransactionType.ADJUSTMENT", "INSERT INTO clinical.stock_balance_reconciliation_lot_counts", "items.setOnHand(itemId, total", "Boolean.TRUE.equals(request.confirmed())")
            .doesNotContain("lots.addReceipt(", "lots.save(", "lots.incrementRemaining(", "lots.decrementRemaining(");
        assertThat(service.indexOf("physicallyCounted.compareTo(lot.getRemainingQuantity())")).isLessThan(service.indexOf("ledger.saveAndFlush("));
        assertThat(service.indexOf("witness.resolve(")).isLessThan(service.indexOf("ledger.saveAndFlush("));
    }
}
