package com.example.hms.architecture;

import com.example.hms.controller.PriceListController;
import com.example.hms.model.PriceListPrice;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 2, guard for the class (owner decision D-4, 2026-11-15: the checkout-date price applies and
 * is preserved on posted lines; plan section 3.1: price history is append-only).
 *
 * <ol>
 *   <li>A price version is never rewritten: every column but {@code effective_to} is {@code updatable = false}, no
 *       main code calls a setter of those fields on a version, {@code setEffectiveTo} on a version is called only by
 *       {@code PriceListService}, and nothing deletes one.</li>
 *   <li>No route edits or deletes a price version (no DELETE or PATCH at all on the controller, no PUT under
 *       {@code /prices}).</li>
 *   <li>The price-list code never touches an invoice or an invoice line, so a price change cannot rewrite a posted
 *       line. (PR 3's tariff resolver reads a version and COPIES its price onto the line; it lives elsewhere and is
 *       not allowed here either.) The one exception, PR 3: {@code PriceListService} ASKS whether an invoice line
 *       copied a version ({@code InvoiceItemRepository.existsByPriceListPriceIdIn}), to refuse correcting it; it
 *       calls nothing else on the invoice lines.</li>
 * </ol>
 */
@DisplayName("Price list: history is append-only and never reaches a posted line")
class PriceListHistoryGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** The fields of a version that never change once it is written. */
    private static final Set<String> FROZEN = Set.of("hospitalId", "item", "unitPrice", "effectiveFrom",
        "changeReason", "createdBy", "importId");

    /** Calls that would rewrite a frozen field of a version, in a file that handles versions. */
    private static final Pattern FROZEN_SETTER = Pattern.compile(
        "\\.set(HospitalId|Item|UnitPrice|EffectiveFrom|ChangeReason|CreatedBy|ImportId)\\(");
    private static final Pattern VERSION_TYPE = Pattern.compile("\\bPriceListPrice\\b");
    /** PR 3: the one invoice-line read the price-list code may make (whether a line copied a version). */
    private static final String ALLOWED_LINE_READ = "invoiceItemRepository.existsByPriceListPriceIdIn(";
    private static final Pattern EFFECTIVE_TO_SETTER = Pattern.compile("\\.setEffectiveTo\\(");
    private static final Pattern DELETE_CALL = Pattern.compile("[pP]riceRepository\\.delete\\w*\\(|"
        + "PriceListPriceRepository[^;]*\\.delete\\w*\\(");

    @Test
    void everyFieldOfAVersionButItsEndIsWrittenOnce() throws NoSuchFieldException {
        for (String name : FROZEN) {
            Field field = PriceListPrice.class.getDeclaredField(name);
            Column column = field.getAnnotation(Column.class);
            JoinColumn join = field.getAnnotation(JoinColumn.class);
            boolean updatable = column != null ? column.updatable() : join == null || join.updatable();
            assertThat(updatable).as(name + " must be updatable = false").isFalse();
        }
        Column end = PriceListPrice.class.getDeclaredField("effectiveTo").getAnnotation(Column.class);
        assertThat(end.updatable()).as("effectiveTo is the one field the service closes").isTrue();
    }

    @Test
    void onlyThePriceListServiceClosesAVersionAndNothingRewritesOrDeletesOne() throws IOException {
        List<String> rewrites = new ArrayList<>();
        Set<String> closers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String text = Files.readString(file, StandardCharsets.UTF_8);
                // A file that handles versions names the type; "getPriceListPriceId" on an invoice line does not.
                if (rel.equals("model/PriceListPrice.java") || !VERSION_TYPE.matcher(text).find()) {
                    continue;
                }
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (FROZEN_SETTER.matcher(line).find()) {
                        rewrites.add(rel + ":" + (i + 1) + " " + line.trim());
                    }
                    if (DELETE_CALL.matcher(line).find()) {
                        rewrites.add(rel + ":" + (i + 1) + " " + line.trim());
                    }
                    if (EFFECTIVE_TO_SETTER.matcher(line).find()) {
                        closers.add(rel);
                    }
                }
            }
        }
        assertThat(rewrites).as("a price version is never rewritten or deleted").isEmpty();
        assertThat(closers).as("only PriceListService closes a version")
            .containsExactly("service/billing/PriceListService.java");
    }

    @Test
    void noRouteEditsOrDeletesAPriceVersion() {
        for (Method method : PriceListController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(DeleteMapping.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(PatchMapping.class)).as(method.getName()).isNull();
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                for (String path : Stream.concat(Stream.of(put.value()), Stream.of(put.path())).toList()) {
                    assertThat(path).as(method.getName()).doesNotContain("prices");
                }
            }
        }
    }

    @Test
    void thePriceListCodeNeverTouchesAnInvoiceOrAPostedLine() throws IOException {
        Pattern invoice = Pattern.compile("InvoiceItem|BillingInvoice|invoice_items|billing_invoices");
        List<String> touches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("PriceList\\w*\\.java|PriceVersionRules\\.java"))
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                // PR 3: the price-list code may ASK whether a line copied a version, and call nothing else on the
                // invoice lines; the repository's import, field and that one read are taken out, then nothing of an
                // invoice may remain.
                int calls = code.split("invoiceItemRepository\\.", -1).length - 1;
                int allowed = code.split(Pattern.quote(ALLOWED_LINE_READ), -1).length - 1;
                if (calls != allowed) {
                    touches.add(MAIN.relativize(file) + " (an invoice-line call other than existsByPriceListPriceIdIn)");
                }
                code = code.replace(ALLOWED_LINE_READ, "")
                    .replace("import com.example.hms.repository.InvoiceItemRepository;", "")
                    .replace("InvoiceItemRepository invoiceItemRepository", "");
                if (invoice.matcher(code).find()) {
                    touches.add(MAIN.relativize(file).toString());
                }
            }
        }
        assertThat(touches).as("price-list code that reaches an invoice or a posted line").isEmpty();
    }
}
