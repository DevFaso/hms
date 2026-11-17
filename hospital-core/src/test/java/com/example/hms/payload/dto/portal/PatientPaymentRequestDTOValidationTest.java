package com.example.hms.payload.dto.portal;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D433: a non-cash patient payment must carry the reference staff confirm it with. */
class PatientPaymentRequestDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static PatientPaymentRequestDTO payment(String method, String reference) {
        return PatientPaymentRequestDTO.builder()
            .amount(new BigDecimal("150.00"))
            .paymentMethod(method)
            .transactionReference(reference)
            .build();
    }

    private Set<ConstraintViolation<PatientPaymentRequestDTO>> violations(PatientPaymentRequestDTO dto) {
        return validator.validate(dto);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD", "BANK_TRANSFER", "MOBILE_MONEY", "card"})
    @DisplayName("a card, transfer or mobile-money payment without a reference is rejected")
    void nonCashWithoutReferenceIsRejected(String method) {
        for (String reference : new String[] {null, "", "   "}) {
            assertThat(violations(payment(method, reference)))
                .as("%s with reference '%s'", method, reference)
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath()).hasToString("referenceGivenForNonCashPayment");
                    assertThat(v.getMessageTemplate()).isEqualTo("{patientPayment.reference.required}");
                });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD", "BANK_TRANSFER", "MOBILE_MONEY"})
    @DisplayName("a non-cash payment with a reference is valid")
    void nonCashWithReferenceIsValid(String method) {
        assertThat(violations(payment(method, "TXN-1"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASH", " cash "})
    @DisplayName("a cash payment needs no reference")
    void cashNeedsNoReference(String method) {
        assertThat(violations(payment(method, null))).isEmpty();
    }
}
