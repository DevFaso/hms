package com.example.hms.architecture;

import com.example.hms.payload.dto.billing.InvoiceLinePreAuthorisationDTO;
import com.example.hms.payload.dto.imaging.ImagingPreAuthorisationDeskDTO;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Insurer claims plan PR 1 (V383, QA D3984, D2712).
 *
 * <ul>
 *   <li>Reception records an imaging order's pre-authorisation number and sees no clinical content (owner rule
 *       2026-11-06): the desk answer and the line answer hold billing fields only. A field added to either fails
 *       here until it is judged non-clinical and listed.</li>
 *   <li>Tenancy (plan 4.5): every query of the payer-share repository, and every query PR 1 added to the invoice-line
 *       repository, names the hospital, so another hospital's share or line is never read or written.</li>
 * </ul>
 */
@DisplayName("Insurer claim data: desk answers hold no clinical field, claim queries name the hospital")
class InsurerClaimDataGuardTest {

    @Test
    @DisplayName("the desk's pre-authorisation answers carry billing fields only")
    void deskAnswersHoldNoClinicalField() {
        assertThat(fieldsOf(ImagingPreAuthorisationDeskDTO.class))
            .containsExactlyInAnyOrder("orderId", "authorizationNumber", "authorizationPending", "invoiceLinesUpdated");
        assertThat(fieldsOf(InvoiceLinePreAuthorisationDTO.class))
            .containsExactlyInAnyOrder("itemId", "invoiceId", "preAuthorisationNumber", "recordedAt", "orderUpdated");
    }

    @Test
    @DisplayName("every payer-share query, and every claim query on invoice lines, names the hospital")
    void claimQueriesNameTheHospital() {
        List<String> offenders = new ArrayList<>();
        for (Method method : InvoicePayerShareRepository.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && !Modifier.isStatic(method.getModifiers()) && !namesHospital(method)) {
                offenders.add("InvoicePayerShareRepository#" + method.getName());
            }
        }
        Set<String> prOne = Set.of("findByIdAndBillingInvoice_IdAndBillingInvoice_Hospital_Id",
            "findByBillingInvoice_IdInAndBillingInvoice_Hospital_Id", "findLiveLineIdsOfOrder",
            "recordPreAuthorisation");
        int found = 0;
        for (Method method : InvoiceItemRepository.class.getDeclaredMethods()) {
            if (prOne.contains(method.getName())) {
                found++;
                if (!namesHospital(method)) {
                    offenders.add("InvoiceItemRepository#" + method.getName());
                }
            }
        }
        assertThat(found).as("the PR 1 line queries").isEqualTo(prOne.size());
        assertThat(offenders).as("queries that do not name the hospital").isEmpty();
    }

    /**
     * Review of #1353 (D-15): every request field that carries a pre-authorisation number refuses a payment-card
     * number, and so does the one rule every service applies to the number.
     */
    @Test
    @DisplayName("every pre-authorisation request field refuses a card number")
    void preAuthorisationFieldsRefuseCardNumbers() throws Exception {
        List<Class<?>> requests = List.of(
            com.example.hms.payload.dto.billing.PreAuthorisationRequestDTO.class,
            com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO.class,
            com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO.class,
            com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO.class);
        for (Class<?> request : requests) {
            assertThat(request.getDeclaredField("authorizationNumber")
                .isAnnotationPresent(com.example.hms.validation.NoCardNumber.class))
                .as(request.getSimpleName() + ".authorizationNumber").isTrue();
        }
        String rule = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/example/hms/service/billing/InvoiceLinePreAuthorisations.java"));
        assertThat(rule).contains("CardNumbers.requireNone(number, field)");
        String imaging = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/example/hms/service/impl/ImagingOrderServiceImpl.java"));
        assertThat(imaging).contains("CardNumbers.requireNone(number, \"authorizationNumber\")");
    }

    private static boolean namesHospital(Method method) {
        if (method.getName().contains("HospitalId") || method.getName().contains("Hospital_Id")) {
            return true;
        }
        for (Parameter parameter : method.getParameters()) {
            Param param = parameter.getAnnotation(Param.class);
            if (param != null && "hospitalId".equals(param.value())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> fieldsOf(Class<?> type) {
        Set<String> names = new TreeSet<>();
        Arrays.stream(type.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .map(Field::getName)
            .forEach(names::add);
        return names;
    }
}
