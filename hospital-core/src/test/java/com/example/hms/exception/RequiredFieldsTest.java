package com.example.hms.exception;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.payload.dto.BillingInvoiceRequestDTO;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.StaffPaymentRequestDTO;
import com.example.hms.service.AppointmentServiceImpl;
import com.example.hms.validation.OnCreate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.context.request.WebRequest;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1299 / D1300 (round 28): an empty body is refused ONCE, with every
 * missing field named under {@code fieldErrors}, in the caller's language.
 */
@DisplayName("D1299 / D1300: one refusal names every missing field")
class RequiredFieldsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void validator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("every failed check is named, in the order checked, and the message joins their reasons")
    void everyMissingFieldIsNamedAtOnce() {
        assertThatThrownBy(() -> RequiredFields.check()
                .require(false, "recipientId", "Choose a recipient")
                .requireValue("  ", "content", "Write a message")
                .requireValue("given", "subject", "never")
                .orThrow())
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> {
                assertThat(refusal.getFieldErrors()).containsExactly(
                    Map.entry("recipientId", "Choose a recipient"), Map.entry("content", "Write a message"));
                assertThat(refusal.getField()).isEqualTo("recipientId");
                assertThat(refusal.getMessage()).isEqualTo("Choose a recipient. Write a message.");
            });
    }

    @Test
    @DisplayName("nothing missing: no refusal")
    void nothingMissingPasses() {
        assertThatCode(() -> RequiredFields.check().requireValue(java.util.List.of(1), "lines", "x")
            .requireValue(5, "amount", "x").orThrow()).doesNotThrowAnyException();
        assertThat(RequiredFields.check().require(false, "a", "x").isEmpty()).isFalse();
    }

    @Test
    @DisplayName("the handler answers every named field under fieldErrors")
    @SuppressWarnings("unchecked")
    void theHandlerAnswersEveryField() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/chat/send");
        FieldBusinessException refusal = new FieldBusinessException(
            new java.util.LinkedHashMap<>(Map.of("recipientId", "A.", "content", "B.")));

        Map<String, Object> body = (Map<String, Object>) handler.handleFieldBusinessException(refusal, request).getBody();

        assertThat((Map<String, String>) body.get("fieldErrors")).containsOnlyKeys("recipientId", "content");
        assertThat(body).containsKey("field");
    }

    @Test
    @DisplayName("D1299: a cancel without its reason names the field the caller sent it in")
    void aStatusReasonRefusalNamesItsField() {
        assertThatThrownBy(() -> AppointmentServiceImpl.requireStatusReason(AppointmentStatus.CANCELLED, " ", "reason"))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getFieldErrors()).containsOnlyKeys("reason"));
        assertThatThrownBy(() -> AppointmentServiceImpl.requireStatusReason(AppointmentStatus.NO_SHOW, null))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getFieldErrors()).containsOnlyKeys("statusReason"));
    }

    @Test
    @DisplayName("D1300: an empty invoice names the patient with the dates (create), and an edit does not need one")
    void anEmptyInvoiceNamesThePatientWithTheDates() {
        assertThat(paths(validator.validate(new BillingInvoiceRequestDTO(), Default.class, OnCreate.class)))
            .contains("patientId", "invoiceDate", "dueDate");
        assertThat(paths(validator.validate(new BillingInvoiceRequestDTO(), Default.class)))
            .doesNotContain("patientId");
    }

    @Test
    @DisplayName("D1300: an empty invoice line names its invoice with the other four (create only)")
    void anEmptyInvoiceLineNamesItsInvoice() {
        assertThat(paths(validator.validate(new InvoiceItemRequestDTO(), Default.class, OnCreate.class)))
            .contains("billingInvoiceId", "itemDescription", "quantity", "unitPrice", "itemCategory");
        assertThat(paths(validator.validate(new InvoiceItemRequestDTO(), Default.class)))
            .doesNotContain("billingInvoiceId");
    }

    @Test
    @DisplayName("D1300: an empty payment names the amount and the method together")
    void anEmptyPaymentNamesAmountAndMethod() {
        assertThat(paths(validator.validate(new StaffPaymentRequestDTO())))
            .contains("amount", "method");
    }

    @Test
    @DisplayName("D1312: the copay method has a label in every language")
    void copayMethodHasALabel() {
        var source = TestMessageSources.bundles();
        assertThat(source.getMessage("field.label.copayMethod", null, Locale.FRENCH)).isEqualTo("mode de paiement");
        assertThat(source.getMessage("field.label.copayMethod", null, Locale.forLanguageTag("es"))).isEqualTo("forma de pago");
        assertThat(source.getMessage("field.label.copayMethod", null, Locale.ENGLISH)).isEqualTo("payment method");
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        Set<String> out = new TreeSet<>();
        violations.forEach(v -> out.add(v.getPropertyPath().toString()));
        return out;
    }
}
