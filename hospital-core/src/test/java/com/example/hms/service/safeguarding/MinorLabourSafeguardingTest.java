package com.example.hms.service.safeguarding;

import com.example.hms.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * r53, provisional rule (user decision 2026-11-14, pending approval): the covered band of a labour admission.
 * The user confirmed a 10-year-old is covered; the provisional default is "under 13 at admission".
 */
@DisplayName("r53: a minor's labour admission opens a safeguarding review, never blocks")
class MinorLabourSafeguardingTest {

    private static final LocalDateTime ADMITTED = LocalDateTime.of(2026, 11, 14, 18, 0);

    private final SafeguardingReviewWorker worker = mock(SafeguardingReviewWorker.class);
    private final MinorLabourSafeguarding screen = new MinorLabourSafeguarding(worker);

    private static Patient agedAt(int years, LocalDateTime on) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        // The day before her birthday: she is still `years` old.
        patient.setDateOfBirth(on.toLocalDate().minusYears(years + 1L).plusDays(1));
        return patient;
    }

    private void admit(Patient patient) {
        screen.onLabourAdmission(patient, UUID.randomUUID(), ADMITTED, "ADMISSION", UUID.randomUUID(),
            UUID.randomUUID());
    }

    @ParameterizedTest(name = "a {0}-year-old opens a review")
    @ValueSource(ints = {8, 10, 12})
    void aChildUnderThirteenOpensAReview(int years) {
        admit(agedAt(years, ADMITTED));
        verify(worker).open(any());
    }

    @ParameterizedTest(name = "a {0}-year-old opens none with the provisional default")
    @ValueSource(ints = {13, 14, 17, 25})
    void thirteenAndOverOpenNone(int years) {
        admit(agedAt(years, ADMITTED));
        verify(worker, never()).open(any());
    }

    @Test
    void theDefaultIsProvisionalUnderThirteen() {
        assertThat(MinorLabourSafeguarding.PROVISIONAL_DEFAULT_AGE_UNDER_YEARS).isEqualTo(13);
        assertThat(screen.ageUnderYears()).isEqualTo(13);
        assertThat(screen.isCovered(10)).isTrue();
        assertThat(screen.isCovered(12)).isTrue();
        assertThat(screen.isCovered(13)).isFalse();
    }

    @Test
    void theAgeIsCountedOnTheAdmissionDayNotToday() {
        // Twelve on the admission day, thirteen the day after: covered.
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(ADMITTED.toLocalDate().minusYears(13).plusDays(1));
        admit(patient);
        verify(worker).open(any());
    }

    @Test
    void theThresholdIsOneConfigurableValue() {
        ReflectionTestUtils.setField(screen, "ageUnderYears", 16);
        admit(agedAt(14, ADMITTED));
        verify(worker).open(any());
    }

    @Test
    void anUnknownBirthDateOpensNoneAndNeverThrows() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        assertThatCode(() -> admit(patient)).doesNotThrowAnyException();
        verify(worker, never()).open(any());
        assertThat(MinorLabourSafeguarding.ageAt(LocalDate.of(2030, 1, 1), ADMITTED.toLocalDate())).isEmpty();
    }

    @Test
    void aFailingWorkerNeverReachesTheAdmission() {
        doThrow(new IllegalStateException("boom")).when(worker).open(any());
        assertThatCode(() -> admit(agedAt(10, ADMITTED))).doesNotThrowAnyException();
    }
}
