package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4181 (r81): a fill prepared from a store whose item had no selling price was charged nothing and handed
 * over unpaid, and every item a stock transfer created was such an item. The class: a path that lets a pharmacy
 * line leave (or creates the item it leaves from) without a price or a documented free-of-charge reason.
 *
 * <p>This guard keeps the class closed across the code base:
 * <ul>
 *   <li>every method that writes a fill (saves a {@code Dispense}, or completes a prepared one) passes
 *       {@code FillPriceGate} first, or is listed below with the reason it writes no new fill;</li>
 *   <li>every place that creates an {@code InventoryItem} gives it a price or a free-of-charge reason from its
 *       source, or is listed below;</li>
 *   <li>"no charge" is never read as "nothing to collect": the payment status tells a free item from an
 *       unpriced one;</li>
 *   <li>the counter sale keeps its own refusal of an unpriced item.</li>
 * </ul>
 */
@DisplayName("QA D4181: nothing leaves the pharmacy unpriced unless it is documented as free")
class UnpricedFillGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path PHARMACY = MAIN.resolve("com/example/hms/service/pharmacy");

    /** The pharmacy's InventoryItem, not the laboratory's LabInventoryItem. */
    private static final Pattern CREATES_ITEM = Pattern.compile(
        "(?<![A-Za-z])InventoryItem\\.builder\\(\\)|new InventoryItem\\(");

    private static final Pattern FILL_WRITE = Pattern.compile(
        "dispenseRepository\\s*\\.\\s*(save|saveAndFlush|completePreparedFill)\\s*\\(");

    /** Methods that write a Dispense row without letting a new fill leave, each with why. */
    private static final Map<String, String> NOT_A_NEW_FILL = Map.of(
        "DispenseServiceImpl#cancelDispense",
        "cancels a fill and puts its stock back; nothing leaves");

    /** Methods that write a fill and reach the gate through the validation they call first. */
    private static final Map<String, String> GATED_BY = Map.of(
        "DispenseServiceImpl#createDispenseTransactionally", "validateFill(",
        "DispenseServiceImpl#markReadyForCollectionTransactionally", "validateFill(",
        "DispenseServiceImpl#handOver", "FillPriceGate.require(");

    @Test
    @DisplayName("every writer of a fill passes the price gate before it writes")
    void everyFillWriterPassesThePriceGate() throws IOException {
        List<String> ungated = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = read(path);
                Matcher write = FILL_WRITE.matcher(source);
                while (write.find()) {
                    String method = path.getFileName().toString().replace(".java", "") + "#"
                        + enclosingMethod(source, write.start());
                    if (NOT_A_NEW_FILL.containsKey(method)) {
                        continue;
                    }
                    String gate = GATED_BY.get(method);
                    String before = source.substring(methodStart(source, write.start()), write.start());
                    if (gate == null || !before.contains(gate)) {
                        ungated.add(method + " writes a fill without " + (gate == null ? "FillPriceGate" : gate));
                    }
                }
            }
        }
        assertThat(ungated)
            .as("a new path that saves or completes a Dispense must call FillPriceGate.require first "
                + "(or be listed in this guard with the reason it lets nothing leave)")
            .isEmpty();
        String dispensing = read(PHARMACY.resolve("DispenseServiceImpl.java"));
        String validateFill = dispensing.substring(dispensing.indexOf("private FillContext validateFill("));
        validateFill = validateFill.substring(0, validateFill.indexOf("\n    }\n"));
        assertThat(validateFill)
            .as("validateFill is the shared validation of the one-step and the prepared fill")
            .contains("FillPriceGate.require(pharmacy, catalogItem, dto.getMedicationName(), inventoryItemRepository)");
    }

    @Test
    @DisplayName("an inventory item is only created priced as its source, or by the item form")
    void everyCreatedInventoryItemHasAPriceSource() throws IOException {
        List<String> creators = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = read(path);
                if (CREATES_ITEM.matcher(source).find()) {
                    creators.add(path.getFileName().toString());
                }
            }
        }
        // The item form (its price is the request's, set by the price roles) and the transfer's destination.
        assertThat(creators)
            .as("a new creator of InventoryItem must carry a selling price or a free-of-charge reason: a "
                + "fill from an unpriced item is refused, so an item created without one blocks its store")
            .containsExactlyInAnyOrder("InventoryItemMapper.java", "StockTransferBooking.java");
        String transfer = read(PHARMACY.resolve("StockTransferBooking.java"));
        assertThat(transfer)
            .contains(".freeOfChargeReason(source.getFreeOfChargeReason())")
            .contains("carryPrice(item, source, movement)")
            .contains("priceHistory.keep(item, before, price, TRANSFER_PRICE_REASON");
        assertThat(countOf(transfer, "carryPrice(item, source, movement)"))
            .as("both the created and the existing destination item take the source's price").isEqualTo(2);
    }

    @Test
    @DisplayName("a fill with no charge is told apart: documented free, or not priced")
    void noChargeIsNeverReadAsNothingToCollect() throws IOException {
        String dispensing = read(PHARMACY.resolve("DispenseServiceImpl.java"));
        assertThat(dispensing)
            .contains("standing == FillPriceGate.Standing.FREE ? \"NO_CHARGE\" : \"NOT_PRICED\"");
        assertThat(read(PHARMACY.resolve("PharmacyListPrice.java")))
            .as("a documented free item has no selling price, whatever figure it still carries")
            .contains("item == null || isFree(item) || item.getUnitPrice() == null");
        assertThat(read(PHARMACY.resolve("PharmacySaleServiceImpl.java")))
            .as("the counter sale refuses an unpriced item").contains("\"pharmacy.sale.noListPrice\"");
        assertThat(read(PHARMACY.resolve("PharmacyPaymentServiceImpl.java")))
            .as("the checkout takes no money for an unpriced fill").contains("\"pharmacy.payment.noPrice\"");
        assertThat(read(PHARMACY.resolve("PharmacyClaimServiceImpl.java")))
            .as("an unpriced fill cannot be claimed").contains("\"pharmacy.claim.noPrice\"");
    }

    private static int countOf(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:(?:public|private|protected|static|final|synchronized)\\s+)*(?=[A-Za-z])[\\w.<>\\[\\], ?]+\\s+"
            + "(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|return\\b|new\\b)(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [\\w., ]+)?\\{");

    private static int methodStart(String source, int position) {
        Matcher method = METHOD.matcher(source);
        int start = 0;
        while (method.find() && method.start() < position) {
            start = method.start();
        }
        return start;
    }

    private static String enclosingMethod(String source, int position) {
        Matcher method = METHOD.matcher(source);
        String name = "?";
        while (method.find() && method.start() < position) {
            name = method.group(1);
        }
        return name;
    }

    /** The file's text with Unix line ends, whatever the checkout wrote. */
    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
