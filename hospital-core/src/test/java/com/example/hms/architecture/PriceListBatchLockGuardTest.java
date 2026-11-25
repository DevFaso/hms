package com.example.hms.architecture;

import com.example.hms.repository.PriceListItemRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A batch holds the existing currency/first-price lock once, never once per inserted row. */
class PriceListBatchLockGuardTest {
    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String STAGE = ".stageItemWithHospitalLockHeld(";

    @Test
    void onlyTheLockedImportMayUseUnqueriedStaging() throws Exception {
        List<String> callers = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(path).contains(STAGE)) {
                    callers.add(MAIN.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(callers).containsExactly("service/billing/PriceListImportService.java");
        String importer = Files.readString(MAIN.resolve("service/billing/PriceListImportService.java"));
        String write = importer.substring(importer.indexOf("private void write("), importer.indexOf("/** What one row"));
        String lock = "itemRepository.lockHospitalWhilePricing(actor.hospitalId());";
        assertThat(write.split(java.util.regex.Pattern.quote(lock), -1).length - 1).isEqualTo(1);
        assertThat(write.indexOf(lock)).isLessThan(write.indexOf("for (Planned row"));
        assertThat(write.indexOf(lock)).isLessThan(write.indexOf(STAGE));

        String service = Files.readString(MAIN.resolve("service/billing/PriceListService.java"));
        assertThat(service).contains("\n    PriceListPrice stageItemWithHospitalLockHeld(");
        assertThat(service).doesNotContain("public PriceListPrice stageItemWithHospitalLockHeld(");
        String stage = service.substring(service.indexOf("    PriceListPrice stageItemWithHospitalLockHeld("),
            service.indexOf("/** A planned version"));
        assertThat(stage).doesNotContain("lockHospitalWhilePricing", ".flush(", ".saveAndFlush(");
        // Standalone creation still takes the same lock; no currency rule is relaxed.
        assertThat(service).contains(lock);
        assertThat(PriceListItemRepository.class.getMethod("lockHospitalWhilePricing", UUID.class)
            .getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_READ);
    }
}
