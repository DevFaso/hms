package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import com.example.hms.utility.PatientMatchKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1103: a patient's name typed exactly as it is displayed - middle name
 * included - finds them, through the one query behind every picker, the
 * reception duplicate check, {@code GET /patients?search=} and
 * {@code /patients/search?name=}. "Rasmané Wend", "Mariam Wendkouni Kiemdé"
 * and "Wendkouni" returned [] on three patients and four pickers.
 */
@TenantScopedDataJpaTest
@Import(PatientMiddleNameSearchTest.JpaConfig.class)
class PatientMiddleNameSearchTest {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PatientHospitalRegistrationRepository registrationRepository;

    @Autowired
    private UserRepository userRepository;

    private Hospital hospital;
    private UUID mariamId;
    private UUID rasmaneId;
    private UUID noMiddleId;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Hôpital A").code("HA").build());
        mariamId = patient("Mariam", "Wendkouni", "Kiemdé", "HA-0001");
        rasmaneId = patient("Rasmané", "Wendlassida", "Ouédraogo", "HA-0002");
        noMiddleId = patient("Awa", null, "Sawadogo", "HA-0003");
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .activeHospitalId(hospital.getId())
            .permittedHospitalIds(Set.of(hospital.getId()))
            .build());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mariam Wendkouni Kiemdé", "mariam wendkouni kiemde", "Wendkouni", "Mariam Wendkouni",
        "Wendkouni Kiemdé", "Kiemdé Mariam Wendkouni", "Mariam Kiemdé", "Kiemdé"})
    @DisplayName("QA D1103: the name as displayed, the middle name alone, or any of its orders finds her")
    void theDisplayedNameFindsThePatient(String typed) {
        assertThat(search(typed)).contains(mariamId).doesNotContain(noMiddleId);
    }

    @Test
    @DisplayName("QA D1103: first name and the start of the middle name, as typed in the reception pickers")
    void firstAndPartOfTheMiddleName() {
        assertThat(search("Rasmané Wend")).containsExactly(rasmaneId);
    }

    @Test
    @DisplayName("a patient with no middle name is still found by first and last name, and nothing else matches")
    void aPatientWithoutAMiddleNameIsUnchanged() {
        assertThat(search("Awa Sawadogo")).containsExactly(noMiddleId);
        assertThat(search("Sawadogo Awa")).containsExactly(noMiddleId);
        assertThat(search("Wendkouni Sawadogo")).isEmpty();
    }

    @Test
    @DisplayName("QA D1103: the exact-identifier lookup also takes the name as displayed")
    void theIdentifierLookupTakesTheDisplayedName() {
        assertThat(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "Mariam Wendkouni Kiemdé"))
            .extracting(r -> r.getPatient().getId()).containsExactly(mariamId);
        assertThat(registrationRepository.findActiveByHospitalIdAndIdentifier(hospital.getId(), "Awa Sawadogo"))
            .extracting(r -> r.getPatient().getId()).containsExactly(noMiddleId);
    }

    private List<UUID> search(String typed) {
        return patientRepository.searchPatientsExtended(null, PatientMatchKeys.nameContainsPattern(typed), null, null,
                null, hospital.getId(), null, PageRequest.of(0, 20))
            .stream().map(Patient::getId).toList();
    }

    private UUID patient(String first, String middle, String last, String mrn) {
        com.example.hms.model.User user = userRepository.save(com.example.hms.model.User.builder()
            .username("u" + mrn).passwordHash("hashed-secret").email(mrn.toLowerCase() + "@example.com")
            .phoneNumber("+2267" + mrn.substring(3) + "0001").firstName(first).lastName(last).build());
        Patient p = patientRepository.save(Patient.builder()
            .user(user)
            .firstName(first).middleName(middle).lastName(last)
            .dateOfBirth(LocalDate.of(1990, 3, 15))
            .phoneNumberPrimary("+2267" + mrn.substring(3) + "0001")
            .hospitalId(hospital.getId())
            .build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(p).hospital(hospital).mrn(mrn).registrationDate(LocalDate.of(2026, 9, 1)).active(true).build());
        return p.getId();
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.example.hms.repository",
        repositoryBaseClass = TenantAwareJpaRepository.class)
    @EntityScan(basePackageClasses = Patient.class)
    static class JpaConfig {
    }
}
