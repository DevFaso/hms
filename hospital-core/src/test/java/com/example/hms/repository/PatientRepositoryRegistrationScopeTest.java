package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9 #57 — the tenant filter scopes a patient by their REGISTRATIONS, not by
 * the hospital they were first seen at.
 *
 * <p>Runs the real {@code TenantAwareJpaRepository} + {@code TenantScopeSpecification}
 * against H2: a patient registered at A and later linked at B must be found by
 * the scoped {@code findById} / {@code existsById} / {@code findAll} from B,
 * and must not be found from C. Before #57 the filter keyed on
 * {@code Patient.hospitalId} (= A) and B saw nothing.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({PatientRepositoryRegistrationScopeTest.JpaConfig.class, TenantContextAccessor.class, EncryptionKeyHolder.class})
class PatientRepositoryRegistrationScopeTest {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PatientHospitalRegistrationRepository registrationRepository;

    @Autowired
    private UserRepository userRepository;

    private Hospital first;
    private Hospital second;
    private Hospital elsewhere;
    private UUID patientId;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        first = hospitalRepository.save(hospital("Hôpital A", "HA"));
        second = hospitalRepository.save(hospital("Hôpital B", "HB"));
        elsewhere = hospitalRepository.save(hospital("Hôpital C", "HC"));

        User user = userRepository.save(User.builder()
            .username("awa.ouedraogo")
            .passwordHash("hashed-secret")
            .email("awa@example.com")
            .phoneNumber("+22670000001")
            .firstName("Awa")
            .lastName("Ouédraogo")
            .build());
        Patient patient = patientRepository.save(Patient.builder()
            .firstName("Awa")
            .lastName("Ouédraogo")
            .dateOfBirth(LocalDate.of(1990, 3, 15))
            .phoneNumberPrimary("+22670000001")
            .user(user)
            .hospitalId(first.getId()) // the FIRST hospital — the old scope key
            .build());
        patientId = patient.getId();

        registrationRepository.save(registration(patient, first, "HA-0001"));
        registrationRepository.save(registration(patient, second, "HB-0001"));
        HospitalContextHolder.clear();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a patient linked to a second hospital is found there by the scoped finders")
    void foundAtTheSecondHospital() {
        scopedTo(second);

        assertThat(patientRepository.findById(patientId)).isPresent();
        assertThat(patientRepository.existsById(patientId)).isTrue();
        assertThat(patientRepository.findAll()).extracting(Patient::getId).containsExactly(patientId);
    }

    @Test
    @DisplayName("and still at the first hospital")
    void stillFoundAtTheFirstHospital() {
        scopedTo(first);

        assertThat(patientRepository.findById(patientId)).isPresent();
    }

    @Test
    @DisplayName("but not from a hospital with no registration for them")
    void notFoundWhereNotRegistered() {
        scopedTo(elsewhere);

        assertThat(patientRepository.findById(patientId)).isEmpty();
        assertThat(patientRepository.existsById(patientId)).isFalse();
        assertThat(patientRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("no scope at all denies, as before")
    void noScopeDenies() {
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(UUID.randomUUID()).build());

        assertThat(patientRepository.findById(patientId)).isEmpty();
    }

    @Test
    @DisplayName("a super-admin sees the patient regardless")
    void superAdminSeesEverything() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());

        assertThat(patientRepository.findById(patientId)).isPresent();
    }

    private static void scopedTo(Hospital hospital) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .activeHospitalId(hospital.getId())
            .permittedHospitalIds(Set.of(hospital.getId()))
            .build());
    }

    private static Hospital hospital(String name, String code) {
        return Hospital.builder().name(name).code(code).build();
    }

    private static PatientHospitalRegistration registration(Patient patient, Hospital hospital, String mrn) {
        return PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn(mrn)
            .registrationDate(LocalDate.of(2026, 9, 1))
            .active(true)
            .build();
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.example.hms.repository",
        repositoryBaseClass = TenantAwareJpaRepository.class)
    @EntityScan(basePackageClasses = Patient.class)
    static class JpaConfig {
    }
}
