package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D779 re-test guard. The whole-unit rule (a counted item never moves by
 * a fraction) was added path by path: returns (D668), then receipts and
 * manual movements (D779 round 1), and the dispense, which had no copy of
 * it, still took 2.5 then 9.5 tablets. Every class that moves stock or
 * records a movement against a lot must ask {@code WholeUnitQuantity}, and
 * no class may keep its own copy of the rule.
 *
 * <p>The allowances may only shrink: each one names why the class takes no
 * caller-supplied quantity.
 */
class StockMovementWholeUnitGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** A call or a row build that moves stock, or records a movement. */
    private static final List<String> MOVEMENT_MARKERS = List.of(
            ".decrementRemaining(", ".incrementRemaining(", ".incrementRemainingWithinReceived(",
            ".addReceipt(", ".decrementOnHand(", ".incrementOnHand(",
            "StockTransaction.builder()", "SaleLine.builder()");

    private static final Map<String, String> ALLOWED = Map.of(
            "PreparedFillVoider.java",
            "reverses a prepared fill: puts back exactly the quantity the fill took, no caller quantity",
            "StockTransactionMapper.java",
            "builds the row for StockTransactionServiceImpl.recordTransaction, which applies the rule first");

    @Test
    void everyStockMovingClassAsksTheWholeUnitRule() throws IOException {
        List<Path> movers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            movers = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("repository"))
                    .filter(f -> MOVEMENT_MARKERS.stream().anyMatch(read(f)::contains))
                    .toList();
        }
        assertThat(movers).as("stock-moving classes").hasSizeGreaterThanOrEqualTo(5);
        for (Path f : movers) {
            if (ALLOWED.containsKey(f.getFileName().toString())) {
                continue;
            }
            assertThat(read(f)).as(f + " moves stock without the whole-unit rule")
                    .contains("WholeUnitQuantity.require");
        }
    }

    @Test
    void theAllowancesStillExist() {
        // A stale allowance would let a new class of the same name slip through.
        for (String name : ALLOWED.keySet()) {
            try (Stream<Path> files = Files.walk(MAIN)) {
                assertThat(files.anyMatch(f -> f.getFileName().toString().equals(name)))
                        .as(name + " is allowed but no longer exists: remove the allowance").isTrue();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    void noClassKeepsItsOwnCopyOfTheRule() throws IOException {
        List<Path> copies;
        try (Stream<Path> files = Files.walk(MAIN)) {
            copies = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("WholeUnitQuantity.java"))
                    .filter(f -> read(f).contains("MEASURED_UNITS") || read(f).contains("\"mcg\", \"µg\""))
                    .toList();
        }
        assertThat(copies).as("classes with their own list of measured units").isEmpty();
    }

    @Test
    void theFillAsksTheRuleBeforeVerificationAndBeforeStockMoves() {
        String src = read(MAIN.resolve("service/pharmacy/DispenseServiceImpl.java"));
        int fill = src.indexOf("private FillContext validateFill(");
        int check = src.indexOf("requireWholeUnitsOfFill(dto, stockLot, prescription);", fill);
        int verify = src.indexOf("dispenseVerificationService.verify(", fill);
        assertThat(fill).isPositive();
        assertThat(check).as("validateFill asks the whole-unit rule").isGreaterThan(fill);
        assertThat(check).as("before verification").isLessThan(verify);
        // Both the one-step fill and the prepared fill go through validateFill.
        assertThat(src.split("validateFill\\(dto, ", -1)).hasSizeGreaterThanOrEqualTo(3);
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
