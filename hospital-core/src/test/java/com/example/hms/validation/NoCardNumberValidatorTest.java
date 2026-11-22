package com.example.hms.validation;

import com.example.hms.payload.dto.StaffPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static com.example.hms.testsupport.RandomIds.withoutRandomIds;
import static org.assertj.core.api.Assertions.assertThat;

/** Billing plan D-15: a payment free-text field never stores a card number. */
@DisplayName("NoCardNumber: a Luhn-valid 13–19 digit value is refused")
class NoCardNumberValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "4111111111111111",                    // Visa test number, 16 digits
        "4111 1111 1111 1111",                 // spaces
        "4111-1111-1111-1111",                 // dashes
        "5555 5555 5555 4444",                 // Mastercard
        "378282246310005",                     // Amex, 15 digits
        "4222222222222",                       // 13 digits
        "6011 0009 9013 9424",                 // Discover
        "carte 4111 1111 1111 1111 exp 12/27", // inside a note
        "4111 1111 1111 1111 2027",            // followed by an expiry year
        "payé par 5555555555554444, merci",
    })
    void aCardNumberIsFound(String value) {
        assertThat(NoCardNumberValidator.containsCardNumber(value)).as(value).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ",
        "OM-778812",                           // an Orange Money reference
        "MP231117.1234.A12345",
        "4111111111111112",                    // 16 digits, fails Luhn
        "411111111111",                        // 12 digits
        "12345678901234567890",                // 20 digits in one block
        "+226 70 20 20 20",                    // a phone number
        "Paiement partiel consultation",
        "VIR-2026-11-17-000123",
    })
    void anOrdinaryReferenceIsKept(String value) {
        assertThat(NoCardNumberValidator.containsCardNumber(value)).as(value).isFalse();
    }

    @Test
    @DisplayName("a staff payment with a card number in its reference or notes is refused on that field, without the value")
    void aStaffPaymentNamesTheField() {
        StaffPaymentRequestDTO payment = new StaffPaymentRequestDTO(new BigDecimal("1000"), "CARD",
            "4111 1111 1111 1111", "CVV 123, carte 5555555555554444");
        Set<ConstraintViolation<StaffPaymentRequestDTO>> violations = VALIDATOR.validate(payment);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
            .containsExactlyInAnyOrder("reference", "notes");
        assertThat(violations).allSatisfy(v -> {
            assertThat(v.getMessageTemplate()).isEqualTo("{" + NoCardNumber.MESSAGE_KEY + "}");
            assertThat(withoutRandomIds(v.getMessage())).doesNotContain("4111").doesNotContain("5555");
        });
    }

    @Test
    @DisplayName("a counter sale with a card number as its reference is refused")
    void aCounterSaleIsRefused() {
        PharmacySaleRequestDTO sale = new PharmacySaleRequestDTO();
        sale.setReferenceNumber("4111-1111-1111-1111");
        assertThat(VALIDATOR.validateProperty(sale, "referenceNumber")).hasSize(1);
        sale.setReferenceNumber("OM-4411");
        assertThat(VALIDATOR.validateProperty(sale, "referenceNumber")).isEmpty();
    }
}
