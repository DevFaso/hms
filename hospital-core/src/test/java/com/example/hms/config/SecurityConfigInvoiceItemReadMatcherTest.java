package com.example.hms.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D973: a receptionist opening an invoice's detail got a 403 on
 * GET /invoice-items/invoice/{id} and the 403 page replaced the screen. The
 * lines are read-only for the front desk: both GETs admit the receptionist,
 * in the filter chain and on the method, while every write still refuses it.
 */
class SecurityConfigInvoiceItemReadMatcherTest {

    private static final Path CONFIG = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");
    private static final Path CONTROLLER =
        Paths.get("src/main/java/com/example/hms/controller/InvoiceItemController.java");

    private static String gateAfter(String source, String line) {
        int at = source.indexOf(line);
        assertThat(at).as(line).isPositive();
        int roles = source.indexOf(".hasAnyAuthority(", at);
        return source.substring(roles, source.indexOf(")", roles));
    }

    @Test
    void receptionistReadsInvoiceLines_butNeverWritesThem() throws IOException {
        String config = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertThat(gateAfter(config, ".requestMatchers(HttpMethod.GET, API_INVOICE_ITEMS_PATTERN)"))
            .contains("ROLE_RECEPTIONIST");
        for (String write : new String[] {"POST", "PUT", "DELETE"}) {
            assertThat(gateAfter(config, ".requestMatchers(HttpMethod." + write + ", API_INVOICE_ITEMS_PATTERN)"))
                .as(write).doesNotContain("ROLE_RECEPTIONIST");
        }
    }

    /**
     * Review of #1338: {@code GET /invoice-items/grid-price} is not a read of the lines but the till's step before it
     * writes one (the price a typed line would take). It admits the line's writers only, never the front desk.
     */
    private static final String WRITE_HELPER_GET = "@GetMapping(\"/grid-price\")";

    @Test
    void bothGetEndpointsAdmitTheReceptionist() throws IOException {
        String controller = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        int from = 0;
        int gets = 0;
        int helpers = 0;
        while ((from = controller.indexOf("@GetMapping", from)) >= 0) {
            int pre = controller.indexOf("@PreAuthorize(", from);
            String annotation = controller.substring(pre, controller.indexOf("\")", pre));
            if (controller.startsWith(WRITE_HELPER_GET, from)) {
                assertThat(annotation).as("the grid price is for the line's writers").doesNotContain("ROLE_RECEPTIONIST")
                    .doesNotContain("ROLE_ACCOUNTANT");
                helpers++;
            } else {
                assertThat(annotation).contains("ROLE_RECEPTIONIST");
                gets++;
            }
            from = pre;
        }
        assertThat(gets).isEqualTo(2);
        assertThat(helpers).isEqualTo(1);
    }
}
