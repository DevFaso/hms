package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3403 / D3415 (r70): a cancelled preparation went back on sale whatever its state, and a returned morphine
 * ampoule went straight back into the saleable lot. The class: any code that writes a RETURN (units coming back to a
 * lot) decides with {@code StockQuarantine.quarantines} whether they may be sold again. A writer that does not is
 * listed here with the reason it is not a physical return.
 */
@DisplayName("QA D3403/D3415: every RETURN writer decides between the shelf and the quarantine")
class ReturnedStockQuarantineGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /**
     * Writers of RETURN rows that are not a physical return of medicine, with why. None: since the review of #1285
     * the cancel of a handed-over fill (DispenseServiceImpl) quarantines a controlled drug too.
     */
    private static final Map<String, String> NOT_A_PHYSICAL_RETURN = Map.of();

    @Test
    void everyReturnWriterUsesTheQuarantineRule() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean writesReturn = source.contains(".transactionType(StockTransactionType.RETURN)")
                        || source.contains("case RECEIPT, RETURN -> putOn");
                if (!writesReturn) {
                    continue;
                }
                writers++;
                String name = file.getFileName().toString();
                if (!source.contains("StockQuarantine.quarantines(") && !NOT_A_PHYSICAL_RETURN.containsKey(name)) {
                    offenders.add(name);
                }
            }
        }
        assertThat(writers).as("the scan finds the RETURN writers").isGreaterThanOrEqualTo(3);
        assertThat(offenders).as("RETURN writers that put stock back on sale without the quarantine rule").isEmpty();
    }
}
