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
            "PharmacyReconciliationService.java", "PharmacyConsumptionService.java"); // reports only read the ledger
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (source.contains("StockTransactionType.TRANSFER") || source.contains("case TRANSFER")) {
                    assertThat(owners).as("new transfer path %s must use the paired booking", path)
                        .contains(path.getFileName().toString());
                    if (path.getFileName().toString().equals("PharmacyConsumptionService.java")) {
                        assertThat(source).doesNotContain(".save(", ".saveAndFlush(", ".delete(", "executeUpdate(");
                    }
                }
            }
        }
        String movement = Files.readString(root.resolve("com/example/hms/service/pharmacy/StockTransactionServiceImpl.java"));
        assertThat(movement).contains("transferBooking.prepare(dto, item)",
            "transferBooking.bookCounterpart(transferRoute, saved)");
        String counterpart = Files.readString(root.resolve("com/example/hms/service/pharmacy/StockTransferBooking.java"));
        assertThat(counterpart).contains("Propagation.MANDATORY", "receive(route.destination(), movement)",
            "takeBack(route.originalReceipt(), movement)");
        assertThat(counterpart).contains("lockItem(source.getId())", "lockLot(request.getStockLotId())",
            "PessimisticLockingFailureException", "stockTransaction.transfer.stockBusy");
        for (String repository : java.util.List.of("InventoryItemRepository.java", "StockLotRepository.java")) {
            assertThat(Files.readString(root.resolve("com/example/hms/repository/pharmacy/" + repository)))
                .contains("FOR UPDATE NOWAIT");
        }
    }
}
