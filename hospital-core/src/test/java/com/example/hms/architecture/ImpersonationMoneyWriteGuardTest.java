package com.example.hms.architecture;

import com.example.hms.security.context.ImpersonationLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 8 review (HIGH): a support session impersonating a user acts as that user, so it could request
 * a refund as a cashier and approve it as an accountant (four-eyes, owner decision D-3, beaten), or let the platform
 * super-admin move money through someone else's account. {@link ImpersonationLimits#MONEY_PATHS} refuses every write
 * on the money paths under impersonation, with an audit row.
 *
 * <p>This scan finds every write endpoint ({@code POST / PUT / PATCH / DELETE}) of every controller, and every one
 * that looks like money (its controller or path names billing, an invoice, a refund, a price, a payment or a sale)
 * must be under a money path, or listed in {@link #NOT_MONEY} with why. A new money endpoint outside the paths fails
 * here. The copay taken inside a check-in (whose path is not money) is refused in the service.
 */
@DisplayName("Every money write endpoint is refused to a support session impersonating the user")
class ImpersonationMoneyWriteGuardTest {

    private static final Pattern LOOKS_LIKE_MONEY = Pattern.compile(
        "(?i)billing|invoice|refund|price|payment|/pay\\b|/pay$|sale");

    /** "METHOD path" -> why a write that looks like money is not one. */
    private static final Map<String, String> NOT_MONEY = new TreeMap<>();

    static {
        NOT_MONEY.put("POST /billing-invoices/search", "a filtered read sent as a POST for its body (MONEY_PATH_READS)");
        NOT_MONEY.put("POST /invoices/{invoiceId}/email", "sends an invoice by e-mail; no amount is set or moved");
        NOT_MONEY.put("POST /invoices/{invoiceId}/send-to", "sends an invoice to an address; no amount is set or moved");
    }

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    @Test
    void everyMoneyWriteIsUnderAMoneyPath() throws Exception {
        List<String> writes = writeEndpoints();
        assertThat(writes).as("write endpoints found").hasSizeGreaterThan(100);

        List<String> uncovered = new ArrayList<>();
        TreeSet<String> covered = new TreeSet<>();
        for (String endpoint : writes) {
            String path = endpoint.substring(endpoint.indexOf(' ') + 1);
            boolean money = LOOKS_LIKE_MONEY.matcher(endpoint).find();
            boolean underMoneyPath = ImpersonationLimits.MONEY_PATHS.stream().anyMatch(p -> PATHS.match(p, path))
                && ImpersonationLimits.MONEY_PATH_READS.stream().noneMatch(p -> PATHS.match(p, path));
            if (underMoneyPath) {
                covered.add(endpoint);
            } else if (money && !NOT_MONEY.containsKey(endpoint)) {
                uncovered.add(endpoint);
            }
        }
        // The refund steps, the payments, a reversal, a price override and the price list are all covered.
        assertThat(covered).contains(
            "POST /billing-invoices/{invoiceId}/refunds",
            "POST /billing-invoices/{invoiceId}/refunds/{refundId}/approve",
            "POST /billing-invoices/{invoiceId}/refunds/{refundId}/reject",
            "POST /billing-invoices/{invoiceId}/refunds/{refundId}/pay",
            "POST /billing-invoices/{invoiceId}/refunds/{refundId}/cancel",
            "POST /billing-invoices/{invoiceId}/payments",
            "POST /billing-invoices/{invoiceId}/payments/{paymentId}/reverse",
            // Billing-money plan PR 7: a payer's decision on its share moves what the patient owes.
            "POST /billing-invoices/{invoiceId}/payer-shares/{shareId}/claim",
            "POST /billing-invoices/{invoiceId}/payer-shares/{shareId}/paid",
            "POST /billing-invoices/{invoiceId}/payer-shares/{shareId}/reject",
            "PUT /invoice-items/{id}",
            "DELETE /invoice-items/{id}",
            "POST /billing/price-list",
            "POST /pharmacy/payments",
            "POST /pharmacy-sales",
            "POST /me/patient/billing/invoices/{invoiceId}/pay",
            "POST /pharmacy/claims/{id}/pay");
        assertThat(uncovered)
            .as("a money write a support session could make as the user: put it under ImpersonationLimits.MONEY_PATHS,"
                + " or list it in NOT_MONEY with why")
            .isEmpty();
        assertThat(NOT_MONEY.keySet()).as("a NOT_MONEY entry that no longer exists: delete it").isSubsetOf(writes);
    }

    @Test
    void theCopayTakenAtCheckInIsRefusedInTheService() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/example/hms/service/BillingInvoiceServiceImpl.java"),
            StandardCharsets.UTF_8);
        int copay = source.indexOf("private PaymentTransaction doRecordCheckInCopay(");
        int next = source.indexOf("\n    }\n", copay);
        assertThat(source.substring(copay, next)).contains("ImpersonationLimits.refuseMoneyWrite(");
    }

    /** "METHOD /path" for every write handler of every controller, with its class prefix. */
    private static List<String> writeEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> found = new ArrayList<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (RequestMethod verb : mapping.method()) {
                    if (verb == RequestMethod.GET || verb == RequestMethod.HEAD || verb == RequestMethod.OPTIONS) {
                        continue;
                    }
                    for (String prefix : prefixes) {
                        for (String path : paths) {
                            found.add(verb + " " + (prefix + path).replace("//", "/"));
                        }
                    }
                }
            }
        }
        return found;
    }
}
