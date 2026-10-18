package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientRecordSharingOptOut;
import com.example.hms.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D110: an opted-out patient's allergies recorded elsewhere stay there. */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("PatientAllergyRepository.findVisibleAt")
class PatientAllergyRepositoryVisibleAtTest {

    @Autowired private PatientAllergyRepository repository;
    @Autowired private TestEntityManager entityManager;

    private Hospital here;
    private Hospital away;
    private Patient patient;

    @BeforeEach
    void setUp() {
        here = hospital("Here");
        away = hospital("Away");
        String n = suffix();
        User user = entityManager.persistAndFlush(User.builder()
            .username("pat" + n).email("pat" + n + "@example.test").passwordHash("x")
            .phoneNumber("7" + n.hashCode()).isActive(true).build());
        patient = entityManager.persistAndFlush(Patient.builder()
            .firstName("Test").lastName("Patient").dateOfBirth(LocalDate.now().minusYears(30))
            .gender("FEMALE").phoneNumberPrimary("6" + Math.abs(n.hashCode())).email("p" + n + "@example.test")
            .hospitalId(here.getId()).user(user).build());
        allergy(here, "Peanuts");
        allergy(away, "Penicillin");
        entityManager.flush();
    }

    private Hospital hospital(String name) {
        String n = suffix();
        return entityManager.persistAndFlush(Hospital.builder()
            .name(name + " " + n).code("AL" + n).address("1 Main St").phoneNumber("555" + n).build());
    }

    private void allergy(Hospital hospital, String display) {
        entityManager.persist(PatientAllergy.builder()
            .patient(patient).hospital(hospital).allergenDisplay(display).build());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void withoutOptOutEveryHospitalsRowTravels() {
        assertThat(repository.findVisibleAt(patient.getId(), here.getId()))
            .extracting(PatientAllergy::getAllergenDisplay)
            .containsExactlyInAnyOrder("Peanuts", "Penicillin");
    }

    @Test
    void anActiveOptOutKeepsOtherHospitalsRowsAway() {
        entityManager.persistAndFlush(PatientRecordSharingOptOut.builder()
            .patient(patient).optedOutAt(LocalDateTime.now()).build());

        assertThat(repository.findVisibleAt(patient.getId(), here.getId()))
            .extracting(PatientAllergy::getAllergenDisplay)
            .containsExactly("Peanuts");
        assertThat(repository.hasActiveSharingOptOut(patient.getId())).isTrue();
    }

    @Test
    void aRevokedOptOutNoLongerApplies() {
        entityManager.persistAndFlush(PatientRecordSharingOptOut.builder()
            .patient(patient).optedOutAt(LocalDateTime.now().minusDays(2)).revokedAt(LocalDateTime.now()).build());

        assertThat(repository.findVisibleAt(patient.getId(), here.getId())).hasSize(2);
        assertThat(repository.hasActiveSharingOptOut(patient.getId())).isFalse();
    }
}
