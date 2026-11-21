package com.example.hms.validation;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2926 (r66): the refills a prescriber writes are a whole number from 0 up. Jackson cuts 1.5 down to 1, so a
 * fraction would be stored as a number nobody wrote; it reads as -1 here and the field's own minimum refuses it.
 */
@DisplayName("The written refills of an order are a whole number, 0 to 99")
class WholeCountDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private PrescriptionRequestDTO read(String refills) {
        return mapper.readValue("{\"medicationName\":\"Amlodipine\",\"refillsAllowed\":" + refills + "}",
            PrescriptionRequestDTO.class);
    }

    private boolean refused(PrescriptionRequestDTO request) {
        return !validator.validateProperty(request, "refillsAllowed").isEmpty();
    }

    @Test
    void wholeNumbersAreRead() {
        assertThat(read("0").getRefillsAllowed()).isZero();
        assertThat(read("3").getRefillsAllowed()).isEqualTo(3);
        assertThat(read("2.0").getRefillsAllowed()).isEqualTo(2);
        assertThat(read("\"4\"").getRefillsAllowed()).isEqualTo(4);
        assertThat(read("null").getRefillsAllowed()).isNull();
        assertThat(refused(read("0"))).isFalse();
        assertThat(refused(read("99"))).isFalse();
        assertThat(refused(read("null"))).as("none written").isFalse();
    }

    @Test
    void aFractionANegativeOrAnAbsurdNumberIsRefusedOnTheField() {
        assertThat(read("1.5").getRefillsAllowed()).as("never cut down to 1").isEqualTo(-1);
        assertThat(refused(read("1.5"))).isTrue();
        assertThat(refused(read("-1"))).isTrue();
        assertThat(refused(read("100"))).isTrue();
    }

    @Test
    void theHoursFieldKeepsItsOwnRefusal() {
        PrescriptionRequestDTO request = mapper.readValue("{\"prnMinIntervalHours\":1.5}", PrescriptionRequestDTO.class);
        assertThat(request.getPrnMinIntervalHours()).as("below its minimum of 1").isZero();
    }
}
