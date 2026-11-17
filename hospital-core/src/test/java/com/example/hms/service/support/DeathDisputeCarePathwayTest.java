package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import com.example.hms.repository.DeathDisputeRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The one rule, for every entry point at once: {@code DeceasedPatientCareGuardTest} pins that each refused entry point
 * calls this guard and that nothing else reads the death, so what is proved here holds across the care pathway. The
 * full emergency pathway through the real services and a real database is
 * {@code DeathDisputeEmergencyPathwayTest}.
 */
class DeathDisputeCarePathwayTest {

    private final UUID hospitalB = UUID.randomUUID();
    private final UUID hospitalA = UUID.randomUUID();
    private Patient patient;
    private DeathDisputeRepository disputes;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDeceasedAt(LocalDateTime.now().minusDays(1));
        disputes = Mockito.mock(DeathDisputeRepository.class);
        new DeathCareEligibility(disputes).afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        DeceasedPatientGuard.useDisputes(null);
    }

    private void actingAt(UUID hospital) {
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospital).permittedHospitalIds(Set.of(hospital)).build());
    }

    private void disputedAt(UUID hospital) {
        when(disputes.existsOpen(patient.getId(), hospital)).thenReturn(true);
    }

    @Test
    @DisplayName("disputed here: every refusal of the guard is lifted, the timed ones too")
    void liftedHere() {
        actingAt(hospitalB);
        disputedAt(hospitalB);

        assertThat(DeceasedPatientGuard.careBlocked(patient)).isFalse();
        assertThat(DeceasedPatientGuard.deathDisputedHere(patient)).isTrue();
        assertThatCode(() -> DeceasedPatientGuard.refuseNewCare(patient)).doesNotThrowAnyException();
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeath(patient, null, "recordedAt")).doesNotThrowAnyException();
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeathDay(patient, null, "readingDate")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disputed at hospital B: the block stays at hospital A")
    void blockedElsewhere() {
        disputedAt(hospitalB);
        actingAt(hospitalA);

        assertThat(DeceasedPatientGuard.careBlocked(patient)).isTrue();
        assertThat(DeceasedPatientGuard.deathDisputedHere(patient)).isFalse();
        assertThatThrownBy(() -> DeceasedPatientGuard.refuseNewCare(patient)).isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("no hospital in scope, or no dispute, keeps the block: the rule fails closed")
    void failsClosed() {
        disputedAt(hospitalB);
        assertThat(DeceasedPatientGuard.careBlocked(patient)).as("no acting hospital").isTrue();

        actingAt(hospitalB);
        DeceasedPatientGuard.useDisputes(null);
        assertThat(DeceasedPatientGuard.careBlocked(patient)).as("no answer").isTrue();
    }

    @Test
    @DisplayName("upheld: the answer turns false and the block is back, explicitly")
    void upheldRestoresTheBlock() {
        actingAt(hospitalB);
        disputedAt(hospitalB);
        assertThat(DeceasedPatientGuard.careBlocked(patient)).isFalse();

        when(disputes.existsOpen(patient.getId(), hospitalB)).thenReturn(false);
        assertThat(DeceasedPatientGuard.careBlocked(patient)).isTrue();
    }

    @Test
    @DisplayName("a living patient never reaches the question")
    void theLiving() {
        patient.setDeceasedAt(null);
        actingAt(hospitalB);

        assertThat(DeceasedPatientGuard.careBlocked(patient)).isFalse();
        Mockito.verifyNoInteractions(disputes);
    }
}
