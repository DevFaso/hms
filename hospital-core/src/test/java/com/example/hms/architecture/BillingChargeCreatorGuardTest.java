package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D936: a visit's copay was billed twice because one charge path did not
 * know another had already charged it. Guard for the class: every place that
 * creates an invoice is listed here with the rule that keeps it from charging
 * the same thing twice, and with the test that pins that rule. A new creator
 * fails this test until it states its rule.
 */
class BillingChargeCreatorGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** Code that builds an invoice. The mapper builds the one createInvoice saves. */
    private static final Pattern CREATES = Pattern.compile("BillingInvoice\\.builder\\(\\)|new BillingInvoice\\(\\)");

    /** Seed data for local development only. */
    private static final List<String> NOT_A_CHARGE_PATH = List.of("bootstrap/DevSyntheticDataSeeder.java");

    /** Creator (file#method) -> its no-double-charge rule and the test that pins it. */
    private static final Map<String, String> RULES = Map.of(
        "mapper/BillingInvoiceMapper.java#toBillingInvoice",
        "createInvoice: one invoice per clientRequestId (QA D94), a second live invoice on a visit only when "
            + "confirmed as supplementary (QA D529), and the form proposing the visit of the patient's open copay "
            + "so it is credited (QA D936) — BillingInvoiceServiceImplTest *_D94, *_D529, billing.spec D936",
        "service/BillingInvoiceServiceImpl.java#doRecordCheckInCopay",
        "one check-in copay per encounter, live or folded, under a row lock on the encounter (QA D936) — "
            + "BillingInvoiceServiceImplTest.aSecondCheckInOfTheSameVisitIsNotBilledAgain_D936",
        "service/DeliveryChargeService.java#chargeDelivery",
        "one delivery per labour episode: LaborServiceImpl refuses a second one (maternity.delivery.alreadyRecorded) "
            + "before the charge is posted");

    @Test
    void everyInvoiceCreatorStatesItsNoDoubleChargeRule() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("model/") || NOT_A_CHARGE_PATH.contains(rel)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (CREATES.matcher(lines.get(i)).find()) {
                        found.merge(rel + "#" + enclosingMethod(lines, i), 1, Integer::sum);
                    }
                }
            }
        }
        assertThat(found.keySet())
            .as("every invoice creator, each with a rule in RULES")
            .containsExactlyInAnyOrderElementsOf(RULES.keySet());
    }

    private static final Pattern METHOD = Pattern.compile(
        "^\\s++(?:public|private|protected)\\s++(?:(?:static|final|synchronized)\\s++)*+[\\w.<>\\[\\],?\\s]+?\\s(\\w++)\\(");

    private static String enclosingMethod(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            Matcher m = METHOD.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1);
            }
        }
        return "?";
    }
}
