package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.PatientMatchKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1239 (round 25): "Awa" filled the picker's eight rows with Sawadogo
 * patients (the text inside their last name) and never offered Awa Kaboré:
 * every match was cut at the page limit in no particular order. The one query
 * behind every picker, the reception duplicate check and the FHIR search now
 * ranks a name equal to the text, then a name starting with it, then a word of
 * the full name starting with it, then any other match - before the limit.
 */
@TenantScopedDataJpaTest
@Import(PatientNameRankingSearchTest.JpaConfig.class)
class PatientNameRankingSearchTest {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PatientHospitalRegistrationRepository registrationRepository;

    @Autowired
    private UserRepository userRepository;

    private Hospital hospital;
    private UUID awaKabore;
    private UUID awaMiddle;
    private int seq;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Hôpital A").code("HA").build());
        // Eight Sawadogo patients first, so an unranked page of 8 holds only them.
        for (String first : List.of("Boukaré", "Djénéba", "Boureima", "Rakiéta", "Bintou", "Salamata", "Ismaël", "Aïssata")) {
            patient(first, null, "Sawadogo");
        }
        awaMiddle = patient("Salif", "Awa", "Ouédraogo");
        awaKabore = patient("Awa", null, "Kaboré");
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

    @Test
    @DisplayName("QA D1239: a first name equal to the text heads the capped page, a middle name at its start follows")
    void anExactFirstNameComesBeforeContainsMatches() {
        Page<Patient> page = search("Awa", PageRequest.of(0, 8));
        assertThat(page.getContent()).extracting(Patient::getId).startsWith(awaKabore, awaMiddle);
        assertThat(page.getTotalElements()).isEqualTo(10);
    }

    @Test
    @DisplayName("QA D1239: a caller's own sort (the FHIR search) only breaks ties after the rank")
    void aCallersSortComesAfterTheRank() {
        Page<Patient> page = search("awa", PageRequest.of(0, 2, Sort.by(Sort.Order.asc("lastName"))));
        assertThat(page.getContent()).extracting(Patient::getId).containsExactly(awaKabore, awaMiddle);
    }

    @Test
    @DisplayName("QA D1239: no text keeps every patient, in a stable order")
    void noTextListsEveryPatient() {
        assertThat(search(null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(10);
    }

    private Page<Patient> search(String typed, PageRequest page) {
        return patientRepository.searchPatientsExtended(null, PatientMatchKeys.nameContainsPattern(typed), null, null,
            null, hospital.getId(), null, page);
    }

    private UUID patient(String first, String middle, String last) {
        seq++;
        String mrn = String.format("HA-%04d", seq);
        com.example.hms.model.User user = userRepository.save(com.example.hms.model.User.builder()
            .username("rank" + seq).passwordHash("hashed-secret").email("rank" + seq + "@example.com")
            .phoneNumber(String.format("+2267%07d", seq)).firstName(first).lastName(last).build());
        Patient p = patientRepository.save(Patient.builder()
            .user(user)
            .firstName(first).middleName(middle).lastName(last)
            .dateOfBirth(LocalDate.of(1990, 3, 15))
            .phoneNumberPrimary(String.format("+2267%07d", seq))
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
