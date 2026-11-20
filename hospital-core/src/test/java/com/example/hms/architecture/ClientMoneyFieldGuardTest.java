package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2671 (r65): a counter sale took the unit price the client sent (1 F CFA instead of 250) although the pharmacy
 * had a list price. The class: a money figure in a request body, trusted where the server already knows the price.
 *
 * <p>Every {@code BigDecimal} money field (price, amount, total, cost, charge, discount, fee) of a request DTO is
 * listed below with how the server treats it, and the listing is checked against the code:
 * <ul>
 *   <li>{@link Kind#CHECKED_AGAINST_SERVER_PRICE}: a server price exists; the named service refuses any other figure
 *       (it must contain the named refusal key);</li>
 *   <li>{@link Kind#BOUNDED_BY_SERVER}: money received or claimed, bounded by what the server says is due (it must
 *       contain the named refusal key);</li>
 *   <li>{@link Kind#SETS_THE_PRICE}: the request IS the price-setting act (price list, inventory selling price);</li>
 *   <li>{@link Kind#NO_SERVER_PRICE}: input that has no server-side price (a purchase cost, a manual invoice).</li>
 * </ul>
 * A new money field fails here until someone decides which it is. A field that claims to be checked must keep the
 * check: removing the refusal from the service fails the test.
 */
@DisplayName("No money endpoint trusts a client amount where a server price exists (QA D2671)")
class ClientMoneyFieldGuardTest {

    private static final Path DTOS = Path.of("src/main/java/com/example/hms/payload/dto");
    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final String DTO_PACKAGE = "com.example.hms.payload.dto";
    private static final Pattern MONEY = Pattern.compile("(?i).*(price|amount|total|cost|charge|discount|fee).*");

    enum Kind { CHECKED_AGAINST_SERVER_PRICE, BOUNDED_BY_SERVER, SETS_THE_PRICE, NO_SERVER_PRICE }

    /** How one field is treated; for the two checked kinds, the service file and the refusal key it must hold. */
    record Treatment(Kind kind, String why, String serviceFile, String refusalKey) {
        static Treatment checked(String why, String serviceFile, String refusalKey) {
            return new Treatment(Kind.CHECKED_AGAINST_SERVER_PRICE, why, serviceFile, refusalKey);
        }

        static Treatment bounded(String why, String serviceFile, String refusalKey) {
            return new Treatment(Kind.BOUNDED_BY_SERVER, why, serviceFile, refusalKey);
        }

        static Treatment setsPrice(String why) {
            return new Treatment(Kind.SETS_THE_PRICE, why, null, null);
        }

        static Treatment noServerPrice(String why) {
            return new Treatment(Kind.NO_SERVER_PRICE, why, null, null);
        }
    }

    private static final Map<String, Treatment> FIELDS = new TreeMap<>();

    static {
        FIELDS.put("SaleLineRequestDTO.unitPrice", Treatment.checked(
            "QA D2671: priced from the pharmacy's list price; another figure is refused",
            "pharmacy/PharmacySaleServiceImpl.java", "pharmacy.sale.priceNotList"));
        FIELDS.put("PharmacyPaymentRequestDTO.amount", Treatment.checked(
            "QA D264: must equal the dispense's charge (quantity × list price)",
            "pharmacy/PharmacyPaymentServiceImpl.java", "pharmacy.payment.amountMismatch"));
        FIELDS.put("InvoiceItemRequestDTO.unitPrice", Treatment.checked(
            "billing plan D-2: a tariff line's price differs from the price list only with a reason and the role",
            "InvoiceItemServiceImpl.java", "billing.item.overrideReasonRequired"));
        FIELDS.put("PharmacyClaimRequestDTO.amount", Treatment.bounded(
            "an insurer's claim, at most the dispense's charge less what the patient paid",
            "pharmacy/PharmacyClaimServiceImpl.java", "pharmacy.claim.amountAboveBillable"));
        FIELDS.put("InvoiceRefundRequestDTO.amount", Treatment.bounded(
            "billing plan PR 8: at most what the invoice can give back",
            "billing/InvoiceRefundService.java", "billing.refund.exceedsRefundable"));
        FIELDS.put("StaffPaymentRequestDTO.amount", Treatment.bounded(
            "money tendered at the till, at most the invoice's balance due",
            "BillingInvoiceServiceImpl.java", "billing.payment.exceedsBalance"));
        FIELDS.put("PatientPaymentRequestDTO.amount", Treatment.bounded(
            "money the patient pays online, at most the invoice's balance due",
            "BillingInvoiceServiceImpl.java", "billing.payment.exceedsBalance"));
        FIELDS.put("CheckInRequestDTO.copayAmount", Treatment.noServerPrice(
            "money taken at the desk at check-in, posted as a payment; the visit is priced by the price list"));
        FIELDS.put("InvoiceAdjustmentRequestDTO.amount", Treatment.noServerPrice(
            "a manual adjustment of an invoice, with its own reason and role"));
        FIELDS.put("BillingInvoiceRequestDTO.totalAmount", Treatment.noServerPrice(
            "a manual invoice with no lines; an invoice with lines takes its total from them"));
        FIELDS.put("BillingInvoiceRequestDTO.amountPaid", Treatment.noServerPrice(
            "legacy manual invoice field; payments are recorded through the payment endpoints"));
        FIELDS.put("PriceListItemRequestDTO.unitPrice", Treatment.setsPrice("the hospital's price list itself"));
        FIELDS.put("PriceListPriceRequestDTO.unitPrice", Treatment.setsPrice("a price version of the price list"));
        FIELDS.put("TreatmentRequestDTO.price", Treatment.setsPrice("a service's own price"));
        FIELDS.put("InventoryItemRequestDTO.unitPrice", Treatment.setsPrice("the pharmacy's selling price (list price)"));
        FIELDS.put("StockLotRequestDTO.unitCost", Treatment.noServerPrice("the purchase cost on the supplier's invoice"));
        FIELDS.put("StockTransactionRequestDTO.unitCost", Treatment.noServerPrice("the purchase cost of a movement"));
    }

    @Test
    @DisplayName("every money field of a request is classified, and every checked one keeps its refusal")
    void everyClientMoneyFieldIsAccountedFor() throws Exception {
        List<String> found = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        for (Class<?> dto : requestDtos()) {
            for (Field field : dto.getDeclaredFields()) {
                if (field.getType() != BigDecimal.class || Modifier.isStatic(field.getModifiers())
                        || !MONEY.matcher(field.getName()).matches()) {
                    continue;
                }
                String key = dto.getSimpleName() + "." + field.getName();
                found.add(key);
                if (!FIELDS.containsKey(key)) {
                    unclassified.add(key);
                }
            }
        }
        assertThat(found).as("the scan must keep finding the counter-sale price this guard was written for")
            .contains("SaleLineRequestDTO.unitPrice", "PharmacyPaymentRequestDTO.amount");
        assertThat(unclassified)
            .as("a money field a client sends: is a server price known for it? Classify it in FIELDS, and if a "
                + "server price exists, refuse any other figure in the service")
            .isEmpty();

        List<String> lostChecks = new ArrayList<>();
        for (Map.Entry<String, Treatment> entry : FIELDS.entrySet()) {
            Treatment t = entry.getValue();
            if (t.serviceFile() == null || !found.contains(entry.getKey())) {
                continue;
            }
            String source = Files.readString(SERVICES.resolve(t.serviceFile()), StandardCharsets.UTF_8);
            if (!source.contains("\"" + t.refusalKey() + "\"")) {
                lostChecks.add(entry.getKey() + " (" + t.serviceFile() + " no longer refuses with " + t.refusalKey() + ")");
            }
        }
        assertThat(lostChecks).as("a checked money field lost its server-side check").isEmpty();
    }

    @Test
    @DisplayName("QA D2671: the counter sale's price is optional (never required from the client)")
    void theCounterSalePriceIsNeverRequired() throws Exception {
        Field unitPrice = Class.forName(DTO_PACKAGE + ".pharmacy.SaleLineRequestDTO").getDeclaredField("unitPrice");
        assertThat(unitPrice.getAnnotation(jakarta.validation.constraints.NotNull.class))
            .as("a required client price invites the client to choose it").isNull();
    }

    private static List<Class<?>> requestDtos() throws IOException, ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DTOS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (!name.endsWith("RequestDTO") && !name.endsWith("Request") && !name.endsWith("RequestDto")) {
                    continue;
                }
                String relative = DTOS.relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
                String fqcn = DTO_PACKAGE + (relative.isEmpty() ? "" : "." + relative) + "." + name;
                out.add(Class.forName(fqcn));
            }
        }
        return out;
    }
}
