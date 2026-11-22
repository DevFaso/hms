package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D2523 (r65): the check-in copay invoice was built with a total and no line, so the billing
 * drawer said « Aucune ligne pour le moment » and its receipt could not say what the money was for.
 * Every invoice the server builds itself ({@code BillingInvoice.builder()} in main code) adds its
 * lines in the same method ({@code addItem} / {@code addLine}): an invoice is never a bare amount.
 * The cashier's own invoices are created through the API with their lines and are not built here.
 */
class ServerInvoiceLinesGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern BUILD = Pattern.compile("\\bBillingInvoice\\.builder\\(\\)");

    @Test
    void everyInvoiceTheServerBuildsCarriesItsLines() throws IOException {
        List<String> offenders = new ArrayList<>();
        int builds = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(f));
                Matcher m = BUILD.matcher(src);
                while (m.find()) {
                    builds++;
                    String method = AppointmentSlotRulesGuardTest.enclosingMethod(src, m.start());
                    if (!method.contains(".addItem(") && !method.contains("addLine(")) {
                        offenders.add(MAIN.relativize(f).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertThat(builds).as("server-built invoices found").isGreaterThanOrEqualTo(4);
        assertThat(offenders).as("an invoice built with no line (QA D2523)").isEmpty();
    }
}
