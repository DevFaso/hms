package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2671 (r65): a counter sale took the unit price the client sent (1 F CFA instead of 250) although the pharmacy
 * had a list price. The class: a money figure in a request body, trusted where the server already knows the price.
 *
 * <p>Every numeric money field (price, amount, total, cost, charge, discount, fee) a controller reads from a request
 * body is listed below with how the server treats it, and the listing is checked against the code. Review of #1239:
 * the scanned set is built from the code, not from a naming habit. It starts at every {@code @RequestBody} parameter
 * of every controller and follows the types it reaches (nested and inner classes, list and map elements, parent
 * classes), and it reads every numeric type ({@code BigDecimal}, {@code Double}, {@code Long}, {@code int}...). It
 * was "the {@code BigDecimal} fields of the files named *RequestDTO": a body named otherwise, an inner class, or an
 * amount typed {@code Double} or {@code Long} was never looked at.
 * <ul>
 *   <li>{@link Kind#CHECKED_AGAINST_SERVER_PRICE}: a server price exists; the named service refuses any other figure
 *       (it must contain the named refusal key);</li>
 *   <li>{@link Kind#BOUNDED_BY_SERVER}: money received or claimed, bounded by what the server says is due (it must
 *       contain the named refusal key);</li>
 *   <li>{@link Kind#SETS_THE_PRICE}: the request IS the price-setting act (price list, inventory selling price);</li>
 *   <li>{@link Kind#NO_SERVER_PRICE}: input that has no server-side price (a purchase cost, a manual invoice);</li>
 *   <li>{@link Kind#NOT_MONEY}: a number whose name only looks like money (a count, a clinical measure).</li>
 * </ul>
 * A new money field fails here until someone decides which it is. A field that claims to be checked must keep the
 * check: removing the refusal from the service fails the test.
 */
@DisplayName("No money endpoint trusts a client amount where a server price exists (QA D2671)")
class ClientMoneyFieldGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final String ROOT_PACKAGE = "com.example.hms";
    private static final String DTO_PACKAGE = "com.example.hms.payload.dto";
    private static final Pattern MONEY = Pattern.compile("(?i)price|amount|total|cost|charge|discount|fee");

    enum Kind { CHECKED_AGAINST_SERVER_PRICE, BOUNDED_BY_SERVER, SETS_THE_PRICE, NO_SERVER_PRICE, NOT_MONEY }

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

        static Treatment notMoney(String why) {
            return new Treatment(Kind.NOT_MONEY, why, null, null);
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
        // Review of #1239: found once the scan read every numeric type of every @RequestBody (a long, an Integer).
        FIELDS.put("SubscriptionPlanRequestDTO.monthlyPriceCents", Treatment.setsPrice(
            "the platform's own subscription plan price, set by the super-admin"));
        FIELDS.put("ImmunizationRequestDTO.totalDosesInSeries", Treatment.notMoney(
            "how many doses a vaccine series has"));
    }

    @Test
    @DisplayName("every money field of a request is classified, and every checked one keeps its refusal")
    void everyClientMoneyFieldIsAccountedFor() throws Exception {
        Set<String> found = moneyFieldsOfRequestBodies();
        List<String> unclassified = found.stream().filter(key -> !FIELDS.containsKey(key)).toList();
        assertThat(found).as("the scan must keep finding the counter-sale price this guard was written for, a line "
                + "reached only through its body's list (the sale line) and a money field that is not a BigDecimal "
                + "(review of #1239: a long)")
            .contains("SaleLineRequestDTO.unitPrice", "PharmacyPaymentRequestDTO.amount",
                "SubscriptionPlanRequestDTO.monthlyPriceCents")
            .containsAll(FIELDS.keySet());
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

    @Test
    @DisplayName("review of #1239: the scan follows a body's inner classes and collections, and reads every numeric type")
    void theScanReachesNestedClassesAndEveryNumericType() {
        Set<Class<?>> reached = new LinkedHashSet<>();
        reach(SampleBody.class, reached);
        assertThat(reached).contains(SampleBody.class, SampleBody.Line.class, SampleBody.Extra.class,
                SampleParent.class);

        Set<String> money = new TreeSet<>();
        reached.forEach(type -> collectMoneyFields(type, money));
        assertThat(money).containsExactlyInAnyOrder("ClientMoneyFieldGuardTest.SampleBody.Line.unitPrice",
                "ClientMoneyFieldGuardTest.SampleBody.Line.discountPercent",
                "ClientMoneyFieldGuardTest.SampleBody.Extra.feeCents",
                "ClientMoneyFieldGuardTest.SampleBody.grandTotal", "ClientMoneyFieldGuardTest.SampleParent.amountDue");
    }

    /** A request body as a controller could declare one: nothing in its name, lines in an inner class. */
    static class SampleBody extends SampleParent {
        List<Line> lines;
        Map<String, Extra> extras;
        long grandTotal;
        String note;
        static final java.math.BigDecimal PRICE_CAP = java.math.BigDecimal.TEN;

        static class Line {
            Double unitPrice;
            int discountPercent;
            String label;
        }

        static class Extra {
            Long feeCents;
        }
    }

    static class SampleParent {
        java.math.BigInteger amountDue;
    }

    // ───────────────────────────── the scan ─────────────────────────────

    /** {@code Owner.field} for every numeric money-named field reachable from a controller's request body. */
    private static Set<String> moneyFieldsOfRequestBodies() throws IOException, ClassNotFoundException {
        Set<Class<?>> reached = new LinkedHashSet<>();
        for (Class<?> controller : classesReadingARequestBody()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class)) {
                        reach(parameter.getParameterizedType(), reached);
                    }
                }
            }
        }
        assertThat(reached).as("the scan found no request body: it is broken, not clean").hasSizeGreaterThan(50);
        Set<String> money = new TreeSet<>();
        reached.forEach(type -> collectMoneyFields(type, money));
        return money;
    }

    /** Every class of the application whose source mentions {@code @RequestBody} (the controllers). */
    private static List<Class<?>> classesReadingARequestBody() throws IOException, ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!Files.readString(file, StandardCharsets.UTF_8).contains("@RequestBody")) {
                    continue;
                }
                String fqcn = MAIN.relativize(file).toString().replace('\\', '.').replace('/', '.')
                        .replaceAll("\\.java$", "");
                out.add(Class.forName(fqcn, false, ClientMoneyFieldGuardTest.class.getClassLoader()));
            }
        }
        return out;
    }

    /** Adds {@code type} and every application class it reaches: type arguments, array elements, fields, parents. */
    private static void reach(Type type, Set<Class<?>> reached) {
        if (type instanceof ParameterizedType parameterized) {
            reach(parameterized.getRawType(), reached);
            for (Type argument : parameterized.getActualTypeArguments()) {
                reach(argument, reached);
            }
        } else if (type instanceof GenericArrayType array) {
            reach(array.getGenericComponentType(), reached);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                reach(bound, reached);
            }
        } else if (type instanceof Class<?> raw) {
            reachClass(raw, reached);
        }
    }

    private static void reachClass(Class<?> raw, Set<Class<?>> reached) {
        if (raw.isArray()) {
            reachClass(raw.getComponentType(), reached);
            return;
        }
        if (raw.isPrimitive() || raw.isEnum() || !raw.getName().startsWith(ROOT_PACKAGE) || !reached.add(raw)) {
            return;
        }
        for (Field field : raw.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                reach(field.getGenericType(), reached);
            }
        }
        if (raw.getSuperclass() != null) {
            reach(raw.getGenericSuperclass(), reached);
        }
    }

    private static void collectMoneyFields(Class<?> type, Set<String> money) {
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && isNumeric(field.getType())
                    && MONEY.matcher(field.getName()).find()) {
                money.add(ownerName(type) + "." + field.getName());
            }
        }
    }

    /** {@code BigDecimal}, {@code BigInteger}, the boxed numbers and the numeric primitives. */
    private static boolean isNumeric(Class<?> type) {
        return Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != boolean.class
                && type != char.class && type != void.class);
    }

    /** {@code Outer.Inner} for an inner class, the simple name otherwise. */
    private static String ownerName(Class<?> type) {
        return type.getDeclaringClass() == null ? type.getSimpleName()
                : ownerName(type.getDeclaringClass()) + "." + type.getSimpleName();
    }
}
