package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** QA D1450 and the bound on POST /billing-invoices/{id}/email. */
class BillingAdjustmentRefusalTextTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @CsvSource({"SENT,", "PARTIALLY_PAID,", "PAID,billing.adjustment.paid",
        "CANCELLED,billing.adjustment.cancelled", "DRAFT,billing.adjustment.notIssued"})
    @DisplayName("QA D1450: each state that refuses says what fits that state")
    void theRefusalFitsTheState(InvoiceStatus status, String key) {
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
            ReflectionTestUtils.invokeMethod(BillingInvoiceServiceImpl.class, "requireAdjustable", status));
        if (key == null) {
            assertThat(refused).isNull();
        } else {
            assertThat(refused).isInstanceOf(com.example.hms.exception.BusinessException.class)
                .hasMessageContaining(key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "_en", "_fr", "_es"})
    @DisplayName("QA D1450: the new refusals exist in every language")
    void theRefusalsAreTranslated(String suffix) throws IOException {
        Properties bundle = new Properties();
        try (var in = Files.newBufferedReader(Paths.get("src/main/resources/i18n/messages-billing" + suffix + ".properties"))) {
            bundle.load(in);
        }
        assertThat(bundle.getProperty("billing.adjustment.paid")).isNotBlank().doesNotContain("draft", "brouillon", "borrador");
        assertThat(bundle.getProperty("billing.adjustment.cancelled")).isNotBlank().doesNotContain("draft", "brouillon", "borrador");
    }

    private static EmailInvoiceRequest request(int to, int cc, int bcc, int messageLength) {
        return new EmailInvoiceRequest(Collections.nCopies(to, "a@example.com"), Collections.nCopies(cc, "b@example.com"),
            Collections.nCopies(bcc, "c@example.com"), "x".repeat(messageLength), Locale.FRENCH.toLanguageTag(), true);
    }

    private static List<String> refusedFields(EmailInvoiceRequest request) {
        return VALIDATOR.validate(request).stream().map(v -> v.getPropertyPath().toString()).sorted()
            .collect(Collectors.toList());
    }

    @Test
    @DisplayName("the mail form's own limits pass")
    void atTheLimitsPasses() {
        assertThat(refusedFields(request(10, 10, 10, 2000))).isEmpty();
    }

    @Test
    @DisplayName("a 20 000-character message and 60 recipients are refused on the fields")
    void pastTheLimitsIsRefusedOnTheFields() {
        assertThat(refusedFields(request(20, 20, 20, 20_000))).containsExactly("bcc", "cc", "message", "to");
    }
}
