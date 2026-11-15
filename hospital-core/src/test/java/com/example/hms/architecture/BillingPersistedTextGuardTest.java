package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1901 (class guard): billing text the server writes was resolved once, in
 * French or in the recorder's language, and stored as plain text, so every
 * reader saw it in that language. Six writers did it (the checkout note, the
 * default consultation, the bed days, a transfusion, the copay cancellation
 * reason, the delivery note and line).
 *
 * <p>The class: a translated message reaching a persisted billing text
 * (an invoice's notes or cancel reason, a line's description, a visit charge)
 * with no message code beside it. Every class that touches an invoice or its
 * lines is scanned; a statement that stores such text may not translate
 * (MessageUtil, a MessageSource, a variable holding their result), and a
 * French copy ({@code BillingText.french()}) must be stored with its code.
 */
@DisplayName("D1901: server-written billing text is stored as a code, never as resolved text alone")
class BillingPersistedTextGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** A class that writes invoices or their lines. */
    private static final Pattern BILLING_CLASS = Pattern.compile("\\b(BillingInvoice|InvoiceItem|VisitCharge)\\b");

    /** The persisted billing texts, and the code each must be stored with. */
    private static final Map<String, String> SINKS = Map.of(
        "notes", "notesCode",
        "cancelReason", "cancelReasonCode",
        "itemDescription", "textCode");

    private static final Pattern SINK = Pattern.compile(
        "\\.(notes|cancelReason|itemDescription)\\(|\\bset(Notes|CancelReason|ItemDescription)\\("
            + "|\\bVisitCharge\\.of\\(|\\bnew\\s+VisitCharge\\(");

    /** A call that turns a key into words in one language. */
    private static final Pattern TRANSLATES = Pattern.compile(
        "\\bMessageUtil\\s*\\.\\s*resolve\\w*\\(|\\bmessageSource\\s*\\.\\s*getMessage\\(|\\.getMessage\\(\\s*\"");

    /** A local variable assigned the result of a translation. */
    private static final Pattern TRANSLATED_VARIABLE = Pattern.compile("\\b(\\w+)\\s*=\\s*[^;=]*?(?:"
        + "\\bMessageUtil\\s*\\.\\s*resolve\\w*\\(|\\bmessageSource\\s*\\.\\s*getMessage\\(|\\.getMessage\\(\\s*\")");

    private static final Pattern FRENCH_COPY = Pattern.compile("\\.french\\(\\)");

    /**
     * Where the stored text is checked, not written: the renderer and the
     * value type themselves. Nothing else may be listed here.
     */
    private static final Set<String> NOT_A_WRITER = Set.of(
        "service/billing/BillingText.java",
        "service/billing/BillingTextRenderer.java");

    /** The six writers QA D1901 found; each must now store a code. */
    private static final List<String> WRITERS = List.of(
        "service/billing/EncounterCheckoutChargeListener.java",
        "service/billing/ConsultationChargeSource.java",
        "service/billing/BedDaysChargeSource.java",
        "service/billing/TransfusionChargeSource.java",
        "service/BillingInvoiceServiceImpl.java",
        "service/DeliveryChargeService.java");

    @Test
    @DisplayName("no billing class stores resolved text without its code")
    void noBillingWriterStoresResolvedText() throws IOException {
        List<String> problems = new ArrayList<>();
        TreeSet<String> scanned = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (NOT_A_WRITER.contains(rel) || rel.startsWith("model/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!BILLING_CLASS.matcher(source).find()) {
                    continue;
                }
                scanned.add(rel);
                for (String problem : violations(source)) {
                    problems.add(rel + ": " + problem);
                }
            }
        }
        assertThat(scanned).as("the scan reaches every writer QA D1901 found").containsAll(WRITERS);
        assertThat(problems).as("a translated text stored on an invoice without its code (QA D1901: store a "
            + "BillingText's french() with its code and args, and show it through BillingTextRenderer)"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("each of the six writers stores the code beside its text")
    void everyKnownWriterStoresACode() throws IOException {
        for (String writer : WRITERS) {
            String source = SourceScan.stripComments(Files.readString(MAIN.resolve(writer), StandardCharsets.UTF_8));
            assertThat(source).as(writer + " words its billing text as a BillingText")
                .containsPattern("BillingText\\.of\\(|VisitCharge\\.worded\\(|BillingText\\s+\\w+\\s*=");
        }
    }

    @Test
    @DisplayName("the guard fails on a writer that stores resolved text without a code")
    void theGuardCatchesAResolvedTextWriter() {
        String direct = """
            class Fixture {
                void write(BillingInvoice invoice) {
                    invoice.setNotes(MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.draftNote", "V-1"));
                }
            }
            """;
        String viaVariable = """
            class Fixture {
                InvoiceItem line() {
                    String label = MessageUtil.resolveOrRaw("maternity.delivery.charge.vaginal");
                    return InvoiceItem.builder().quantity(1).itemDescription(label).build();
                }
            }
            """;
        String visitCharge = """
            class Fixture {
                VisitCharge charge(Optional<Treatment> stay) {
                    return VisitCharge.of(stay.map(Treatment::getName)
                        .orElseGet(() -> MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.bedDays")),
                        ItemCategory.SERVICE, 1, stay);
                }
            }
            """;
        String frenchWithoutCode = """
            class Fixture {
                BillingInvoice draft(BillingText note) {
                    return BillingInvoice.builder().notes(note.french()).build();
                }
            }
            """;
        String coded = """
            class Fixture {
                BillingInvoice draft(BillingText note, BillingInvoice copay, BillingText reason) {
                    copay.setCancelReason(reason.french());
                    copay.setCancelReasonCode(reason.code());
                    copay.setCancelReasonArgs(reason.argsJson());
                    return BillingInvoice.builder().notes(note.french()).notesCode(note.code()).build();
                }
            }
            """;
        assertThat(violations(direct)).hasSize(1);
        assertThat(violations(viaVariable)).hasSize(1);
        assertThat(violations(visitCharge)).hasSize(1);
        assertThat(violations(frenchWithoutCode)).hasSize(1);
        assertThat(violations(coded)).isEmpty();
    }

    // ------------------------------------------------------------------

    static List<String> violations(String raw) {
        String source = SourceScan.stripComments(raw);
        Set<String> translatedVariables = new LinkedHashSet<>();
        Matcher assigned = TRANSLATED_VARIABLE.matcher(source);
        while (assigned.find()) {
            translatedVariables.add(assigned.group(1));
        }
        List<String> problems = new ArrayList<>();
        Matcher sink = SINK.matcher(source);
        while (sink.find()) {
            int start = SourceScan.statementStart(source, sink.start());
            int end = SourceScan.indexOfOutsideLiterals(source, ';', sink.end());
            String statement = source.substring(start, end < 0 ? source.length() : end);
            String sinkName = sinkName(sink);
            if (TRANSLATES.matcher(statement).find()) {
                problems.add("stores a translated text: " + oneLine(statement));
                continue;
            }
            String variable = translatedVariables.stream()
                .filter(v -> Pattern.compile("\\b" + Pattern.quote(v) + "\\b").matcher(statement).find())
                .findFirst().orElse(null);
            if (variable != null) {
                problems.add("stores '" + variable + "', a translated text: " + oneLine(statement));
                continue;
            }
            if (sinkName != null && FRENCH_COPY.matcher(statement).find()
                    && !storesCode(source, start, end, SINKS.get(sinkName))) {
                problems.add("stores a French copy without its code (" + SINKS.get(sinkName) + "): "
                    + oneLine(statement));
            }
        }
        return problems;
    }

    /** The persisted field the sink writes, or null for a visit charge (worded through VisitCharge.worded). */
    private static String sinkName(Matcher sink) {
        String name = sink.group(1) != null ? sink.group(1) : sink.group(2);
        if (name == null) {
            return null;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** The code is stored in the same builder, or by a setter within the next three statements. */
    private static boolean storesCode(String source, int start, int end, String codeField) {
        int from = end < 0 ? source.length() : end;
        int to = from;
        for (int i = 0; i < 3 && to >= 0 && to < source.length(); i++) {
            to = SourceScan.indexOfOutsideLiterals(source, ';', to + 1);
        }
        String window = source.substring(start, to < 0 ? source.length() : to);
        String setter = "set" + Character.toUpperCase(codeField.charAt(0)) + codeField.substring(1);
        return window.contains("." + codeField + "(") || window.contains(setter + "(");
    }

    private static String oneLine(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }
}
