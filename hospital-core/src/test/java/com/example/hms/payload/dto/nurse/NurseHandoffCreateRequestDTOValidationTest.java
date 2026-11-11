package com.example.hms.payload.dto.nurse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1371 (round 31): {@code {patientId:null, recipientUserId:null,
 * direction:'BOGUS', situation:''}} was refused naming three fields; the
 * unknown handoff type was named one request later (a service-only check).
 */
class NurseHandoffCreateRequestDTOValidationTest {

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

    private static Set<String> invalidFields(NurseHandoffCreateRequestDTO request) {
        Set<ConstraintViolation<NurseHandoffCreateRequestDTO>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void d1371_anUnknownTypeIsNamedWithTheOtherRefusals() {
        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .direction("BOGUS").situation("").build();
        assertThat(invalidFields(request))
            .containsExactlyInAnyOrder("patientId", "recipientUserId", "direction", "situation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIFT_CHANGE", "shift_change", " WARD_TRANSFER ", "PROCEDURE", "Break_Cover"})
    void d1371_everyTypeTheSelectSendsPasses(String direction) {
        NurseHandoffCreateRequestDTO request = NurseHandoffCreateRequestDTO.builder()
            .patientId(UUID.randomUUID()).recipientUserId(UUID.randomUUID())
            .direction(direction).situation("Post-op day 1").build();
        assertThat(invalidFields(request)).isEmpty();
    }

    /** The edge pattern and the service's list are one vocabulary. */
    @Test
    void d1371_thePatternAcceptsExactlyTheTypes() {
        Pattern edge = Pattern.compile(NurseHandoffCreateRequestDTO.DIRECTION_PATTERN, Pattern.CASE_INSENSITIVE);
        for (String code : NurseHandoffCreateRequestDTO.DIRECTIONS) {
            assertThat(edge.matcher(code).matches()).as(code).isTrue();
        }
        String pattern = NurseHandoffCreateRequestDTO.DIRECTION_PATTERN;
        String alternatives = pattern.substring(pattern.indexOf("(?:") + 3, pattern.indexOf(')'));
        assertThat(Set.of(alternatives.split("\\|"))).isEqualTo(NurseHandoffCreateRequestDTO.DIRECTIONS);
        assertThat(edge.matcher("SHIFT").matches()).isFalse();
        assertThat(edge.matcher("SHIFT_CHANGE_X").matches()).isFalse();
    }
}
