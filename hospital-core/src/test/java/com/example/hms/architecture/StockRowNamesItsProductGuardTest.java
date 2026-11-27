package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4184 (r81), class: a stock list keyed by lot that does not name the medicine. The movement list showed
 * « Transfert −20 R81-A-1 » and the expiry report a lot, a date and a quantity; lot numbers repeat across medicines
 * (R81-A-1 was a lot of Métronidazole 250 mg and of 500 mg), so neither row could be attributed.
 *
 * <p>Server: a lot and a movement are given their product in ONE place each ({@code StockLotMapper},
 * {@code StockTransactionMapper}), from {@code StockProductLabel}; nothing else builds those read models.
 * Portal: a pharmacy template that puts a lot number in a table cell also puts the product on the page.
 */
class StockRowNamesItsProductGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path PHARMACY_PAGES = Path.of("../hospital-portal/src/app/pharmacy");

    /** A table cell that prints a lot's number. */
    private static final Pattern LOT_CELL = Pattern.compile(
        "<td[^>]*>(?:(?!</td>)[\\s\\S])*?\\b(?:lotNumber|stockLotNumber)\\b(?:(?!</td>)[\\s\\S])*?</td>");
    /** What names the product beside it: the server's name for the row, or the page's own name of the item. */
    private static final Pattern PRODUCT = Pattern.compile(
        "\\b(?:productName|medicationName|productLabel|getItemName|drugName)\\b");

    /**
     * Templates whose lot table needs no product column, each with why. Shrink only: a new lot table is given
     * its product, not an entry here.
     */
    private static final Map<String, String> PRODUCT_FIXED_BY_CONTEXT = Map.of();

    @Test
    void aLotAndAMovementAreGivenTheirProductInOnePlaceEach() throws IOException {
        String lots = Files.readString(MAIN.resolve("mapper/pharmacy/StockLotMapper.java"));
        String movements = Files.readString(MAIN.resolve("mapper/pharmacy/StockTransactionMapper.java"));
        assertThat(lots).contains(".productName(com.example.hms.service.pharmacy.StockProductLabel.nameOf(medicine))",
            ".strength(com.example.hms.service.pharmacy.StockProductLabel.strengthOf(medicine))",
            ".form(com.example.hms.service.pharmacy.StockProductLabel.formOf(medicine))");
        assertThat(movements).contains(".medicationName(com.example.hms.service.pharmacy.StockProductLabel.nameOf(medicine))",
            ".strength(com.example.hms.service.pharmacy.StockProductLabel.strengthOf(medicine))",
            ".form(com.example.hms.service.pharmacy.StockProductLabel.formOf(medicine))",
            ".unit(entity.getUnitSnapshot())");

        List<String> otherBuilders = new ArrayList<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String file = MAIN.relativize(path).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(path));
                if ((source.contains("StockLotResponseDTO.builder()") || source.contains("new StockLotResponseDTO("))
                        && !file.equals("mapper/pharmacy/StockLotMapper.java")) {
                    otherBuilders.add(file + " builds a StockLotResponseDTO");
                }
                if ((source.contains("StockTransactionResponseDTO.builder()")
                        || source.contains("new StockTransactionResponseDTO("))
                        && !file.equals("mapper/pharmacy/StockTransactionMapper.java")) {
                    otherBuilders.add(file + " builds a StockTransactionResponseDTO");
                }
            }
        }
        assertThat(otherBuilders)
            .as("a lot or a movement built outside its mapper is listed without its medicine (QA D4184)")
            .isEmpty();
    }

    @Test
    void everyMovementListReadIsGivenItsTransferStore() throws IOException {
        String service = SourceScan.stripComments(
            Files.readString(MAIN.resolve("service/pharmacy/StockTransactionServiceImpl.java")));
        // The four lists and the single read: none maps a page straight through the mapper any more.
        assertThat(service).doesNotContain(".map(stockTransactionMapper::toResponseDTO)");
        assertThat(service.split("withTransferStores\\(", -1).length - 1)
            .as("the reads of the ledger that go through withTransferStores, and its declaration").isEqualTo(6);
    }

    @Test
    void aPharmacyPageThatListsLotsNamesTheirProduct() throws IOException {
        List<String> unnamed = new ArrayList<>();
        List<String> listing = new ArrayList<>();
        try (var paths = Files.walk(PHARMACY_PAGES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".html")).toList()) {
                String file = PHARMACY_PAGES.relativize(path).toString().replace('\\', '/');
                String template = Files.readString(path);
                if (!LOT_CELL.matcher(template).find()) {
                    continue;
                }
                listing.add(file);
                if (!PRODUCT.matcher(template).find() && !PRODUCT_FIXED_BY_CONTEXT.containsKey(file)) {
                    unnamed.add(file);
                }
            }
        }
        assertThat(listing).as("the scan still finds the lot tables it is about")
            .contains("inventory-dashboard.html", "stock-adjustment.html");
        assertThat(unnamed)
            .as("a table that prints a lot number must also name the medicine (QA D4184): lot numbers repeat")
            .isEmpty();
        assertThat(PRODUCT_FIXED_BY_CONTEXT.keySet()).as("an allowance for a page that no longer lists lots is removed")
            .isSubsetOf(listing);

        // The two lists the defect was found on name the product IN the row, not merely somewhere on the page.
        String expiry = Files.readString(PHARMACY_PAGES.resolve("inventory-dashboard.html"));
        assertThat(expiry).contains("lot.productName");
        String ledger = Files.readString(PHARMACY_PAGES.resolve("stock-adjustment.html"));
        assertThat(ledger).contains("productOf(tx)", "tx.counterpartPharmacyName");
    }
}
