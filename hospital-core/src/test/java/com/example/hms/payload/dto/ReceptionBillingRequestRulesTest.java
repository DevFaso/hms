package com.example.hms.payload.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReceptionBillingRequestRulesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> refused(Object body) {
        Set<String> out = new java.util.TreeSet<>();
        for (ConstraintViolation<Object> violation : validator.validate(body)) {
            out.add(violation.getPropertyPath().toString());
        }
        return out;
    }

    @Test
    @DisplayName("D1415: check-in refuses a false or absent identityConfirmed on its field, and takes true")
    void checkInNeedsTheIdentityConfirmed() {
        CheckInRequestDTO absent = CheckInRequestDTO.builder().appointmentId(UUID.randomUUID()).build();
        CheckInRequestDTO refusedOne = CheckInRequestDTO.builder().appointmentId(UUID.randomUUID()).identityConfirmed(false).build();
        CheckInRequestDTO confirmed = CheckInRequestDTO.builder().appointmentId(UUID.randomUUID()).identityConfirmed(true).build();
        assertThat(refused(absent)).contains("identityConfirmed");
        assertThat(refused(refusedOne)).contains("identityConfirmed");
        assertThat(refused(confirmed)).doesNotContain("identityConfirmed");
    }

    @Test
    @DisplayName("D1418: an invoice line description over the column's 255 is refused on its field")
    void invoiceLineDescriptionLength() {
        InvoiceItemRequestDTO line = new InvoiceItemRequestDTO();
        line.setItemDescription("x".repeat(256));
        assertThat(refused(line)).contains("itemDescription");
        line.setItemDescription("x".repeat(255));
        assertThat(refused(line)).doesNotContain("itemDescription");
    }
}
