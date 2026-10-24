package com.example.hms.validation;

import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D714/D745 (round 10 re-test, mat.js): labour admission now+3h, delivery
 * now+2h, newborn assessment now+5h and maternal history now+5d all answered
 * 201. The controllers validate these DTOs with {@code @Valid}, so each must
 * fail bean validation on exactly that field.
 */
class ClinicalTimestampFutureValidationTest {

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

    private static Set<String> fields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
            .filter(v -> v.getConstraintDescriptor().getAnnotation() instanceof NotInClinicalFuture)
            .map(v -> v.getPropertyPath().toString())
            .collect(Collectors.toSet());
    }

    @Test
    void labourAdmissionThreeHoursAheadIsRefused() {
        LaborEpisodeRequestDTO dto = LaborEpisodeRequestDTO.builder()
            .laborOnsetAt(LocalDateTime.now().minusHours(1))
            .admittedAt(LocalDateTime.now().plusHours(3))
            .build();
        assertThat(fields(validator.validate(dto))).containsExactly("admittedAt");
    }

    @Test
    void deliveryTwoHoursAheadIsRefused() {
        DeliveryRecordRequestDTO dto = DeliveryRecordRequestDTO.builder()
            .birthDateTime(LocalDateTime.now().plusHours(2)).build();
        assertThat(fields(validator.validate(dto))).containsExactly("birthDateTime");
    }

    @Test
    void newbornAssessmentFiveHoursAheadIsRefused() {
        NewbornAssessmentRequestDTO dto = NewbornAssessmentRequestDTO.builder()
            .assessmentTime(LocalDateTime.now().plusHours(5)).build();
        assertThat(fields(validator.validate(dto))).containsExactly("assessmentTime");
    }

    @Test
    void maternalHistoryFiveDaysAheadIsRefused() {
        MaternalHistoryRequestDTO dto = MaternalHistoryRequestDTO.builder()
            .patientId(UUID.randomUUID()).hospitalId(UUID.randomUUID())
            .recordedDate(LocalDateTime.now().plusDays(5))
            .menstrualHistory(MaternalHistoryRequestDTO.MenstrualHistoryDTO.builder()
                .lastMenstrualPeriod(LocalDate.now().plusDays(10))
                .estimatedDueDate(LocalDate.now().plusMonths(8))
                .build())
            .build();
        // The nested LMP is checked too; the due date is a planned date and stays free.
        assertThat(fields(validator.validate(dto)))
            .containsExactlyInAnyOrder("recordedDate", "menstrualHistory.lastMenstrualPeriod");
    }

    @Test
    void partographEntryFiveHoursAheadIsRefused() {
        PartographEntryRequestDTO dto = new PartographEntryRequestDTO();
        dto.setObservationTime(LocalDateTime.now().plusHours(5));
        assertThat(fields(validator.validate(dto))).containsExactly("observationTime");
    }

    @Test
    void pastValuesAndASmallDeviceSkewPass() {
        LaborEpisodeRequestDTO dto = LaborEpisodeRequestDTO.builder()
            .laborOnsetAt(LocalDateTime.now().minusHours(2))
            .admittedAt(LocalDateTime.now().plusMinutes(5))
            .build();
        assertThat(fields(validator.validate(dto))).isEmpty();
    }

    @Test
    void theRuleUsesTheGivenClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-24T09:00:00Z"), ZoneOffset.UTC);
        assertThat(NotInClinicalFutureValidator.isAllowed(LocalDateTime.parse("2026-10-24T09:09:59"), clock)).isTrue();
        assertThat(NotInClinicalFutureValidator.isAllowed(LocalDateTime.parse("2026-10-24T09:10:01"), clock)).isFalse();
        assertThat(NotInClinicalFutureValidator.isAllowed(LocalDate.parse("2026-10-25"), clock)).isFalse();
        assertThat(NotInClinicalFutureValidator.isAllowed(LocalDate.parse("2026-10-24"), clock)).isTrue();
    }
}
