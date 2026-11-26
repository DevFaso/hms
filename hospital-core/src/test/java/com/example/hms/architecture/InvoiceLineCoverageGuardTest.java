package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3980 (r77), the class: a line a cashier typed on an insured invoice (Widal, a health booklet, syringes) carried
 * no category and no price-list item, so no plan rule could read it and the plan's default 75 % was claimed from the
 * insurer (3 150 F too much on 000016). D3603 had fixed the same hole for hand-priced imaging lines only.
 *
 * <p>The coverage now fails closed ({@code InvoiceCoverageService.coveredNet}: a line with no category is never
 * covered), so every place that creates an invoice line must say what the line bills, or be a known place whose lines
 * are never claimed. This guard finds every invoice-line creation in the main code:
 * <ul>
 *   <li>an {@code InvoiceItem.builder()} chain must set {@code .coverageCategory(} or {@code .priceListItemId(} before
 *       its {@code .build()}, unless it is listed in {@link #NEVER_COVERED} with the reason;</li>
 *   <li>a {@code new InvoiceItem(} must be listed in {@link #RESOLVED_ELSEWHERE}, naming the method that resolves its
 *       act, and that method must exist in the named class and link the price list.</li>
 * </ul>
 * A new invoice-line source fails here until it resolves its coverage.
 */
@DisplayName("QA D3980: every invoice line resolves what a payer plan sees it as")
class InvoiceLineCoverageGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern BUILDER = Pattern.compile("InvoiceItem\\s*+\\.\\s*+builder\\(\\)");
    private static final Pattern CONSTRUCTOR = Pattern.compile("new\\s++InvoiceItem\\s*+\\(");
    private static final Pattern RESOLVES = Pattern.compile("\\.coverageCategory\\(|\\.priceListItemId\\(");

    /** Files whose builder makes a line no plan ever covers, and why. */
    private static final Map<String, String> NEVER_COVERED = Map.of(
        "BillingInvoiceServiceImpl.java",
        "copayLine: the patient's own share paid at check-in, on a copay invoice the coverage skips (isCheckInCopay)");

    /** Files that construct a line by hand, and the class and method that resolve its act before it is saved. */
    private static final Map<String, String[]> RESOLVED_ELSEWHERE = Map.of(
        "InvoiceItemMapper.java",
        new String[] {"service/InvoiceItemServiceImpl.java", "linkToPriceList"});

    @Test
    @DisplayName("no invoice-line creation path skips coverage resolution")
    void everyInvoiceLineSourceResolvesItsCoverage() throws IOException {
        List<String> builders = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> constructed = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                if ("InvoiceItem.java".equals(name)) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher builder = BUILDER.matcher(code);
                while (builder.find()) {
                    String site = name + ":" + SourceScan.lineOf(code, builder.start());
                    builders.add(name);
                    int end = code.indexOf(".build()", builder.end());
                    String chain = end < 0 ? code.substring(builder.end()) : code.substring(builder.end(), end);
                    if (!RESOLVES.matcher(chain).find() && !NEVER_COVERED.containsKey(name)) {
                        missing.add(site);
                    }
                }
                Matcher constructor = CONSTRUCTOR.matcher(code);
                while (constructor.find()) {
                    constructed.add(name);
                    if (!RESOLVED_ELSEWHERE.containsKey(name)) {
                        missing.add(name + ":" + SourceScan.lineOf(code, constructor.start()) + " (new InvoiceItem)");
                    }
                }
            }
        }
        // The scan must keep finding the sources it was written for.
        assertThat(builders).as("the invoice-line builders").contains("EncounterCheckoutChargeListener.java",
            "DeliveryChargeService.java", "BillingInvoiceServiceImpl.java");
        assertThat(constructed).as("the hand-built lines").contains("InvoiceItemMapper.java");
        assertThat(missing).as("invoice lines created with no category and no price-list item").isEmpty();
    }

    @Test
    @DisplayName("a line typed at the till takes its act from the price list, and the coverage fails closed")
    void theTypedLineAndTheCoverageResolveIt() throws IOException {
        for (String[] resolver : RESOLVED_ELSEWHERE.values()) {
            String code = SourceScan.stripComments(Files.readString(MAIN.resolve(resolver[0]),
                StandardCharsets.UTF_8));
            String body = SourceScan.methodBody(code, resolver[1]);
            assertThat(body).as(resolver[0] + "#" + resolver[1]).isNotNull();
            assertThat(body).contains("setPriceListItemId(").contains("setCoverageCategory(");
            assertThat(SourceScan.methodBody(code, "createInvoiceItem")).contains(resolver[1] + "(");
        }
        String coverage = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/billing/InvoiceCoverageService.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(coverage, "compute")).as("the coverage of a line with no category")
            .contains("coveredNet(");
    }
}
