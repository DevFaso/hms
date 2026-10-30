package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1084 guard: a time the server writes into text in pharmacy or billing
 * is printed as stored (the server's UTC) instead of on the hospital's wall
 * clock. The controlled register's reason said "dispensed 30/10 17:09" while
 * its time column, converted by the portal, said 13:09.
 *
 * <p>A pharmacy or billing source that builds a date-and-time formatter
 * ({@code ofLocalizedDateTime}, or a pattern with hours) must convert what it
 * prints through {@code HospitalTime}. A date alone is not checked (a stored
 * {@code LocalDate} is already the hospital's day).
 */
@DisplayName("Server-composed times in pharmacy and billing are on the hospital's clock (QA D1084)")
class ServerComposedTimeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern DATE_TIME_FORMATTER = Pattern.compile(
        "ofLocalizedDateTime\\(|ofPattern\\(\\s*\"[^\"]*H[^\"]*\"|appendPattern\\(\\s*\"[^\"]*H[^\"]*\"");
    private static final Pattern CONVERTS = Pattern.compile("\\bHospitalTime\\.");

    /** Known sources that print server time as stored, each with why it is not fixed here. Shrink only. */
    private static final Map<String, String> ALLOWED = Map.of(
        "service/PdfLabels.java",
        "QA D1084 sibling: fmt(LocalDateTime) prints a payment time on the invoice PDF and receipt as stored; it "
            + "has no hospital, and its caller PdfInvoiceService belongs to the front-desk branch "
            + "(fix/r18-front-desk, D1036/D1091), which must pass HospitalTime.zoneOf(invoice.getHospital())");

    @Test
    @DisplayName("every pharmacy or billing date-time formatter converts through HospitalTime")
    void serverComposedTimesAreLocal() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (!inScope(rel)) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (DATE_TIME_FORMATTER.matcher(source).find() && !CONVERTS.matcher(source).find()) {
                    offenders.add(rel);
                }
            }
        }
        assertThat(offenders.stream().filter(o -> !ALLOWED.containsKey(o)).toList())
            .as("a pharmacy/billing time printed in the server's zone (QA D1084)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(ALLOWED.keySet().stream().filter(a -> !offenders.contains(a)).toList())
            .as("an allowed source now converts: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1084 shape")
    void catchesTheOldShape() {
        String before = "String date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(at);";
        assertThat(DATE_TIME_FORMATTER.matcher(before).find() && !CONVERTS.matcher(before).find()).isTrue();
        String after = before.replace("format(at)", "format(HospitalTime.at(at, zone))");
        assertThat(DATE_TIME_FORMATTER.matcher(after).find() && !CONVERTS.matcher(after).find()).isFalse();
    }

    /** Pharmacy and billing: their packages, and the billing services, mappers and PDF labels at the root. */
    static boolean inScope(String rel) {
        return rel.startsWith("service/pharmacy/") || rel.startsWith("service/billing/")
            || rel.startsWith("mapper/pharmacy/") || rel.startsWith("controller/pharmacy/")
            || rel.matches("(service|mapper|controller)/(Billing|Invoice|Payment|Pharmacy|Pdf)\\w*\\.java");
    }
}
