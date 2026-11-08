package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1181 class (round 22): a weight, a height, a glucose or a pain score
 * recorded alone restarted the nurse's 4-hour vitals round, exactly as a mood
 * note restarted the postpartum 15-minute clock. The round's clock runs from
 * the latest OBSERVATION SET, on the real query.
 */
@DataJpaTest
@ActiveProfiles("test")
class PatientVitalSignObservationSetQueryTest {

    @Autowired private PatientVitalSignRepository repository;
    @Autowired private TestEntityManager entityManager;

    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        String n = suffix();
        hospital = entityManager.persistAndFlush(Hospital.builder()
            .name("Vitals " + n).code("VS" + n).address("1 Main St").phoneNumber("555" + n).build());
        User user = entityManager.persistAndFlush(User.builder()
            .username("vs" + n).email("vs" + n + "@example.test").passwordHash("x")
            .phoneNumber("7" + Math.abs(n.hashCode())).isActive(true).build());
        patient = entityManager.persistAndFlush(Patient.builder()
            .firstName("Test").lastName("Patient").dateOfBirth(LocalDate.now().minusYears(30))
            .gender("FEMALE").phoneNumberPrimary("6" + Math.abs(n.hashCode())).email("p" + n + "@example.test")
            .hospitalId(hospital.getId()).user(user).build());
    }

    @Test
    void aMeasurementAloneIsNotTheLatestObservationSet() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 8, 0);
        PatientVitalSign round = row(t, v -> v.setHeartRateBpm(88));
        row(t.plusHours(3), v -> v.setWeightKg(72.0));
        row(t.plusHours(3).plusMinutes(5), v -> v.setHeightCm(175.0));
        row(t.plusHours(3).plusMinutes(10), v -> v.setPainScore(0));
        row(t.plusHours(3).plusMinutes(15), v -> v.setBloodGlucoseMgDl(110));
        row(t.plusHours(3).plusMinutes(20), v -> v.setOnOxygen(Boolean.FALSE));
        entityManager.flush();

        assertThat(repository.findLatestObservationSet(patient.getId(), hospital.getId()))
            .get().extracting(PatientVitalSign::getId).isEqualTo(round.getId());
        assertThat(repository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patient.getId(), hospital.getId()))
            .get().extracting(PatientVitalSign::getId).isNotEqualTo(round.getId());
    }

    @Test
    void eachRoundReadingAloneMakesAnObservationSet() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 8, 0);
        java.util.List<java.util.function.Consumer<PatientVitalSign>> readings = java.util.List.of(
            v -> v.setTemperatureCelsius(37.1), v -> v.setHeartRateBpm(80), v -> v.setRespiratoryRateBpm(16),
            v -> v.setSystolicBpMmHg(120), v -> v.setDiastolicBpMmHg(80), v -> v.setSpo2Percent(98),
            v -> v.setConsciousnessLevel(com.example.hms.enums.ConsciousnessLevel.values()[0]));
        for (int i = 0; i < readings.size(); i++) {
            PatientVitalSign set = row(t.plusMinutes(i), readings.get(i));
            entityManager.flush();
            assertThat(repository.findLatestObservationSet(patient.getId(), hospital.getId()))
                .as("reading %d", i).get().extracting(PatientVitalSign::getId).isEqualTo(set.getId());
        }
    }

    private PatientVitalSign row(LocalDateTime at, java.util.function.Consumer<PatientVitalSign> reading) {
        PatientVitalSign v = PatientVitalSign.builder().patient(patient).hospital(hospital).recordedAt(at).build();
        reading.accept(v);
        return entityManager.persist(v);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
