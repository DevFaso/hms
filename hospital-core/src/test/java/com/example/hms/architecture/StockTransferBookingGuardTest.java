package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class StockTransferBookingGuardTest {
    @Test
    void everyTransferWriterMustBookItsCounterpartInTheSameTransaction() throws Exception {
        Path root = Path.of("src/main/java");
        Set<String> owners = Set.of("StockTransactionServiceImpl.java", "StockTransferBooking.java",
            "PharmacyReconciliationService.java", "PharmacyConsumptionService.java", "StockTransactionRepository.java"); // reports and repository queries only read the ledger
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (source.contains("StockTransactionType.TRANSFER") || source.contains("case TRANSFER")) {
                    assertThat(owners).as("new transfer path %s must use the paired booking", path)
                        .contains(path.getFileName().toString());
                    if (Set.of("PharmacyConsumptionService.java", "StockTransactionRepository.java")
                            .contains(path.getFileName().toString())) {
                        assertThat(source).doesNotContain(".save(", ".saveAndFlush(", ".delete(", "executeUpdate(");
                    }
                }
            }
        }
        String movement = Files.readString(root.resolve("com/example/hms/service/pharmacy/StockTransactionServiceImpl.java"));
        assertThat(movement).contains("transferBooking.prepare(dto, item)",
            "transferBooking.bookCounterpart(transferRoute, saved)");
        String counterpart = Files.readString(root.resolve("com/example/hms/service/pharmacy/StockTransferBooking.java"));
        assertThat(counterpart).contains("findByTransferOriginId(original.getId())", ".transferOriginId(source.getId())",
            "original.getReferenceId()", "original.getInventoryItem().getMedicationCatalogItem().getId()")
            .doesNotContain("findByReferenceIdAndTransactionType(");
        assertThat(movement.indexOf("transferBooking.bookCounterpart(transferRoute, saved)"))
            .isLessThan(movement.indexOf("applyQuantityChange(item, lot, dto.getTransactionType(), dto.getQuantity())"));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!path.getFileName().toString().equals("StockTransferBooking.java")) {
                    assertThat(Files.readString(path)).as("only the paired booking owns this identity: " + path)
                        .doesNotContain(".setTransferOriginId(", ".transferOriginId(");
                }
            }
        }
        String transactionRepository = Files.readString(root.resolve("com/example/hms/repository/pharmacy/StockTransactionRepository.java"));
        assertThat(transactionRepository).contains("r.transferOriginId = t.id", "receipt.transferOriginId = t.id", "findReturnablePairedTransfers");
        assertThat(Files.readString(root.resolve("com/example/hms/payload/dto/pharmacy/StockTransactionRequestDTO.java")))
            .doesNotContain("transferOriginId");
        String returnScreen = Files.readString(Path.of("../hospital-portal/src/app/pharmacy/stock-adjustment.ts"));
        assertThat(returnScreen).contains("this.svc.listReturnableTransfers(itemId, lotId || undefined)",
            "this.form.referenceId = this.returnDispenseId || this.returnTransferId || undefined",
            "this.isReturn() && !this.returnTransferId");
        assertThat(counterpart).contains("Propagation.MANDATORY", "receive(route.destination(), movement)",
            "takeBack(route.originalReceipt(), movement, route.fefoOverrideReason())");
        assertThat(counterpart).contains("lockItem(source.getId())", "lockLot(request.getStockLotId())",
            "StockRowLocks.isContention(failure)", "stockTransaction.transfer.stockBusy");
        assertThat(counterpart).contains("StockLotBalance.requireReconciled(item, lots.sumRemainingForItem(item.getId()))",
            "StockLotBalance.requireLotlessReturn(lots.existsByInventoryItemId(item.getId()))");
        assertThat(Files.readString(root.resolve("com/example/hms/service/pharmacy/StockRowLocks.java")))
            .contains("PessimisticLockingFailureException", "\"55P03\"");
        for (String repository : java.util.List.of("InventoryItemRepository.java", "StockLotRepository.java")) {
            assertThat(Files.readString(root.resolve("com/example/hms/repository/pharmacy/" + repository)))
                .contains("FOR UPDATE NOWAIT");
        }
    }
}
