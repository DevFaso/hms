package com.example.hms.security.audit;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4226 (round 81), the guard of the class. A handler that states DETAILS on its request's audit row
 * ({@link WriteAuditDetail#describe(com.example.hms.enums.AuditEventType, java.util.UUID, java.util.UUID, Map)})
 * did something the route alone does not say: the journal read « Création : Facture » for an invoice mailed
 * outside the hospital, and the recipients it kept could be seen nowhere. Every such handler is listed here with
 * its route, and the portal's audit journal must name that route ({@code WRITE_ROUTE_KEYS}) so the row reads as
 * what was done and opens its detail.
 */
@DisplayName("Audit journal: a write that keeps details is named by the portal")
class WriteAuditDetailRouteLabelGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path PORTAL_ROUTES =
        Path.of("../hospital-portal/src/app/audit-logs/audit-description.service.ts");

    /** The classes that state details on their audit row, and the write route each serves. */
    private static final Map<String, String> ROUTE_OF = Map.of(
        "InvoiceEmailService.java", "POST /billing-invoices/{id}/email");

    @Test
    void everyHandlerThatKeepsDetailsIsListedWithItsRoute() throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (file.getFileName().toString().equals("WriteAuditDetail.java")) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (statesDetails(code)) {
                    found.put(file.getFileName().toString(), "");
                }
            }
        }
        assertThat(found.keySet())
            .as("a new handler states details on its audit row: name its route in the portal's WRITE_ROUTE_KEYS "
                + "(audit-description.service.ts), show its details in the journal's detail panel, then list it here")
            .containsExactlyInAnyOrderElementsOf(ROUTE_OF.keySet());
    }

    @Test
    void thePortalNamesEachOfThoseRoutes() throws IOException {
        String portal = Files.readString(PORTAL_ROUTES, StandardCharsets.UTF_8);
        int start = portal.indexOf("WRITE_ROUTE_KEYS");
        assertThat(start).as("the portal's route names").isPositive();
        String routes = portal.substring(start, portal.indexOf("};", start));
        for (String route : ROUTE_OF.values()) {
            assertThat(routes).as("the audit journal names the route %s", route).contains("'" + route + "'");
        }
    }

    /** Whether the code calls {@code WriteAuditDetail.describe} with a fourth argument (the details). */
    private static boolean statesDetails(String code) {
        String call = "WriteAuditDetail.describe(";
        int at = code.indexOf(call);
        while (at >= 0) {
            int open = at + call.length() - 1;
            int close = SourceScan.closingParen(code, open);
            if (close > open && topLevelCommas(code.substring(open + 1, close)) >= 3) {
                return true;
            }
            at = code.indexOf(call, at + 1);
        }
        return false;
    }

    private static int topLevelCommas(String arguments) {
        int depth = 0;
        int commas = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }
        return commas;
    }
}
