package com.example.hms.architecture;

import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PharmacyPaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing plan PR 11 (QA D2430): a cash payment whose answer was lost was shown as failed although it was saved, and
 * the retry recorded it twice. Every endpoint that takes money must accept the client's idempotency key, so a retry
 * carrying it answers with the payment already recorded.
 *
 * <p>This scan finds every write endpoint ({@code POST / PUT / PATCH}) of every controller whose request body names a
 * way of paying (a field typed {@link PaymentMethod}, {@link PharmacyPaymentMethod} or {@link MobileMoneyOperator},
 * or a field named {@code paymentMethod}). That body must declare a {@link UUID} field named
 * {@code clientRequestId} (or ending in {@code ClientRequestId}), or the endpoint is listed in {@link #NOT_KEYED} with
 * why. A new money-writing endpoint without a key fails here.
 */
@DisplayName("Every endpoint that takes money accepts a client idempotency key")
class PaymentIdempotencyKeyGuardTest {

    /** The types whose presence on a request body says it records money handed over. */
    private static final Set<Class<?>> TENDER_TYPES = Set.of(PaymentMethod.class, PharmacyPaymentMethod.class,
        MobileMoneyOperator.class);
    /** A String payment method ({@code method} alone is too common: a culture's or a consent's method is not money). */
    private static final Set<String> TENDER_FIELD_NAMES = Set.of("paymentMethod");

    /** "METHOD path" -> why a body that names a way of paying needs no key. */
    private static final Map<String, String> NOT_KEYED = new TreeMap<>();

    static {
        NOT_KEYED.put("POST /billing-invoices/{invoiceId}/refunds/{refundId}/pay",
            "pays out one APPROVED refund: a transition of that row under the invoice lock; a replay finds it PAID"
                + " and is refused, nothing is paid twice (the refund REQUEST carries its key, V307)");
        NOT_KEYED.put("POST /pharmacy/refunds",
            "QA D2676 (V311): asks for the refund of one RETURN movement; at most one live refund per movement"
                + " (uk_prefund_live_return, checked under the sale or dispense lock): a replay is refused, never doubled");
        NOT_KEYED.put("POST /pharmacy/refunds/{id}/pay",
            "QA D2676: pays out one APPROVED refund under its row lock; a replay finds it PAID and answers with it");
        NOT_KEYED.put("POST /me/patient/billing/invoices/{invoiceId}/pay",
            "a patient's declaration: a PENDING row that counts only once a cashier confirms it; a duplicate is"
                + " rejected at the desk (key: standing debt, the mobile apps would need to send it too)");
    }

    /** The four writers D2430 named: they must stay keyed. */
    private static final Set<String> KEYED_WRITERS = Set.of(
        "POST /billing-invoices/{invoiceId}/payments",
        "POST /reception/check-in",
        "POST /pharmacy/payments",
        "POST /pharmacy-sales");

    @Test
    void everyMoneyTakingEndpointAcceptsAKey() throws Exception {
        Map<String, Class<?>> bodies = writeBodies();
        assertThat(bodies).as("write endpoints with a body found").hasSizeGreaterThan(100);

        TreeSet<String> takingMoney = new TreeSet<>();
        List<String> unkeyed = new ArrayList<>();
        TreeSet<String> keyed = new TreeSet<>();
        bodies.forEach((endpoint, body) -> {
            if (!namesAWayOfPaying(body)) {
                return;
            }
            takingMoney.add(endpoint);
            if (hasKey(body)) {
                keyed.add(endpoint);
            } else if (!NOT_KEYED.containsKey(endpoint)) {
                unkeyed.add(endpoint + " (" + body.getSimpleName() + ")");
            }
        });

        assertThat(keyed).as("the four writers of D2430 accept a key").containsAll(KEYED_WRITERS);
        assertThat(unkeyed)
            .as("an endpoint that takes money without a client idempotency key: add a UUID clientRequestId to its"
                + " body and answer a replay with the first result (billing plan PR 11), or list it in NOT_KEYED"
                + " with why")
            .isEmpty();
        assertThat(NOT_KEYED.keySet()).as("a NOT_KEYED entry that no longer takes money: delete it")
            .isSubsetOf(takingMoney);
    }

    private static boolean namesAWayOfPaying(Class<?> body) {
        for (Class<?> type = body; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (TENDER_TYPES.contains(field.getType()) || TENDER_FIELD_NAMES.contains(field.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasKey(Class<?> body) {
        for (Class<?> type = body; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                boolean named = field.getName().equals("clientRequestId") || field.getName().endsWith("ClientRequestId");
                if (named && field.getType() == UUID.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "METHOD /path" -> the @RequestBody type, for every write handler of every controller. */
    private static Map<String, Class<?>> writeBodies() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Class<?>> found = new TreeMap<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                Class<?> body = bodyOf(method);
                if (mapping == null || body == null) {
                    continue;
                }
                String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
                for (RequestMethod verb : mapping.method()) {
                    if (verb != RequestMethod.POST && verb != RequestMethod.PUT && verb != RequestMethod.PATCH) {
                        continue;
                    }
                    for (String prefix : prefixes) {
                        for (String path : paths) {
                            found.put(verb + " " + (prefix + path).replace("//", "/"), body);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static Class<?> bodyOf(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return parameter.getType();
            }
        }
        return null;
    }
}
