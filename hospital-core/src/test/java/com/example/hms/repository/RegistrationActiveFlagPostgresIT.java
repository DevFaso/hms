package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1476 on the real engine: the question every desk visit opener asks, for each state a
 * patient can be in at a hospital (active, deactivated, no registration at all).
 */
@Testcontainers
@TenantScopedDataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RepositoryNullTextParameterPostgresIT.JpaConfig.class)
class RegistrationActiveFlagPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_d1476")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private PatientHospitalRegistrationRepository repository;
    @Autowired private TestEntityManager em;

    private Hospital hospital;
    private Organization organization;

    @BeforeEach
    void setUp() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        String uid = UUID.randomUUID().toString().substring(0, 8);
        organization = em.persist(HospitalFixtures.organization("Org " + uid, "ORG-" + uid));
        hospital = em.persist(HospitalFixtures.hospital(organization, "Test Hospital", "HOSP-" + uid));
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    private Patient patient(String name) {
        String key = name + UUID.randomUUID().toString().substring(0, 8);
        User user = em.persist(User.builder().username(key).passwordHash("h").email(key + "@example.com")
            .phoneNumber("+226" + key).firstName(name).lastName("Yoni").build());
        return em.persist(Patient.builder().firstName(name).lastName("Yoni").dateOfBirth(LocalDate.of(1990, 1, 1))
            .gender("FEMALE").address("Rue 1").phoneNumberPrimary("+226" + key).email(key + "@example.com").user(user)
            .hospitalId(hospital.getId()).organizationId(organization.getId()).active(true).build());
    }

    private void register(Patient patient, boolean active) {
        em.persist(PatientHospitalRegistration.builder().patient(patient).hospital(hospital)
            .mrn("m" + UUID.randomUUID().toString().substring(0, 8)).registrationDate(LocalDate.now())
            .active(active).build());
        em.flush();
    }

    @Test
    void onlyADeactivatedRegistrationAnswersYes() {
        Patient deactivated = patient("Pauline");
        register(deactivated, false);
        Patient active = patient("Awa");
        register(active, true);
        Patient elsewhere = patient("Mariam");

        assertThat(repository.existsByPatientIdAndHospitalIdAndActiveFalse(deactivated.getId(), hospital.getId())).isTrue();
        assertThat(repository.existsByPatientIdAndHospitalIdAndActiveFalse(active.getId(), hospital.getId())).isFalse();
        assertThat(repository.existsByPatientIdAndHospitalIdAndActiveFalse(elsewhere.getId(), hospital.getId())).isFalse();
    }
}
