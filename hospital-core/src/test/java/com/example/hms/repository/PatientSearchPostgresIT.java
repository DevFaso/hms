package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.PatientMatchKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1253 (round 26, High regression): every patient search by phone, MRN,
 * birth date or e-mail answered 500 on dev - "function
 * character_length(bytea) does not exist". The D1239 ranking clause handed
 * {@code :namePattern} to LENGTH and SUBSTRING; with no name typed the value
 * is null, pgjdbc sends it as bytea, and PostgreSQL refuses the statement.
 * The unit test ran on H2, which accepts it.
 *
 * <p>This runs both patient searches on a real PostgreSQL built by the
 * changelog, for EVERY combination of their filters - each one set to the
 * patient's value or left null (2^7 = 128 for the extended search, the shape
 * every picker, the reception duplicate check and the FHIR search send; 2^4
 * for the short one) - unsorted (the service and the duplicate check) and
 * sorted by name (the FHIR search). Each combination must run and must find
 * the patient; a wrong value must find nobody.
 */
@TenantScopedDataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PatientSearchPostgresIT.JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientSearchPostgresIT {

    private static final String MRN = "CHW7213";
    private static final String PHONE = "+22670260122";
    private static final String EMAIL = "kadi.sorgho@example.com";
    private static final LocalDate BIRTH = LocalDate.of(1993, 11, 3);
    private static final int FILTERS = 7;

    // Started here, not by @Container: the per-class lifecycle loads the
    // context (and reads the URL) before the Testcontainers extension runs.
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_patient_search")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    static {
        POSTGRES.start();
    }

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

    @Autowired private PatientRepository patientRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID hospitalId;
    private UUID kadidiatou;
    private UUID other;

    @BeforeAll
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Hospital hospital = hospitalRepository.save(Hospital.builder().name("Hôpital A").code("HA-D1253").build());
            hospitalId = hospital.getId();
            kadidiatou = patient(hospital, "Kadidiatou", "Sorgho", MRN, PHONE, EMAIL, BIRTH);
            other = patient(hospital, "Awa", "Kaboré", "CHW0001", "+22665112233", "awa.kabore@example.com",
                LocalDate.of(1988, 2, 14));
        });
        HospitalContextHolder.clear();
    }

    @BeforeEach
    void bindToTheHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .activeHospitalId(hospitalId)
            .permittedHospitalIds(Set.of(hospitalId))
            .build());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @AfterAll
    void removeRows() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            registrationRepository.deleteAll(registrationRepository.findAll().stream()
                .filter(r -> r.getHospital().getId().equals(hospitalId)).toList());
            List<Patient> seeded = patientRepository.findAllById(List.of(kadidiatou, other));
            List<UUID> users = seeded.stream().map(p -> p.getUser().getId()).toList();
            patientRepository.deleteAll(seeded);
            userRepository.deleteAllById(users);
            hospitalRepository.deleteById(hospitalId);
        });
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("QA D1253: every filter combination of the extended search runs on PostgreSQL and finds the patient")
    void everyCombinationOfTheExtendedSearchFindsThePatient() {
        List<String> failures = new ArrayList<>();
        for (Pageable page : List.of(PageRequest.of(0, 20),
            PageRequest.of(0, 20, Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"))))) {
            for (int mask = 0; mask < (1 << FILTERS); mask++) {
                try {
                    List<UUID> found = extended(mask, page, false);
                    if (!found.contains(kadidiatou)) {
                        failures.add(describe(mask) + " missed the patient: " + found);
                    }
                } catch (RuntimeException e) {
                    failures.add(describe(mask) + " " + page.getSort() + " threw " + rootMessage(e));
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("QA D1253: a set filter with a wrong value finds nobody, whatever else is null")
    void aWrongValueInAnyFilterFindsNobody() {
        List<String> failures = new ArrayList<>();
        for (int filter = 0; filter < FILTERS; filter++) {
            if (filter == 5 || filter == 6) {
                continue; // hospital and active have no "wrong" value that keeps the scope; covered below
            }
            List<UUID> found = extended(1 << filter, PageRequest.of(0, 20), true);
            if (!found.isEmpty()) {
                failures.add(describe(1 << filter) + " (wrong value) found " + found);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(patientRepository.searchPatientsExtended(null, null, null, null, null, hospitalId, false,
            PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("QA D1253: the reception pickers' phone, MRN, birth-date and e-mail searches, exactly as typed in round 26")
    void theRoundTwentySixSearches() {
        assertThat(ids(patientRepository.searchPatientsExtended(null, null, null,
            PatientMatchKeys.phoneContainsPattern("70260122"), null, hospitalId, null, PageRequest.of(0, 8))))
            .containsExactly(kadidiatou);
        assertThat(ids(patientRepository.searchPatientsExtended(MRN, null, null, null, null, hospitalId, true,
            PageRequest.of(0, 8)))).containsExactly(kadidiatou);
        assertThat(ids(patientRepository.searchPatientsExtended(null, null, "1993-11-03", null, null, hospitalId, true,
            PageRequest.of(0, 8)))).containsExactly(kadidiatou);
        assertThat(ids(patientRepository.searchPatientsExtended(null, null, null, null, "%kadi%", hospitalId, true,
            PageRequest.of(0, 8)))).containsExactly(kadidiatou);
        // the name ranking (D1239) still holds on PostgreSQL
        assertThat(ids(patientRepository.searchPatientsExtended(null, PatientMatchKeys.nameContainsPattern("awa"),
            null, null, null, hospitalId, true, PageRequest.of(0, 8)))).startsWith(other);
    }

    /**
     * QA D1274 (round 27): the values the service now sends for a birth date
     * typed in each accepted format and for an e-mail (full, its start with
     * the @, or its local part alone), with the hospital and the active flag
     * each set or null - the picker (inactive included), the API default and
     * the exact-identifier lookup.
     */
    @Test
    @DisplayName("QA D1274: a typed birth date (any format) and an e-mail (full or partial) find the patient on PostgreSQL")
    void theRoundTwentySevenBirthDateAndEmailSearches() {
        List<String> failures = new ArrayList<>();
        List<String> dobs = List.of("1993-11-03", "03/11/1993", "03-11-1993", "3/11/1993", "03.11.1993");
        List<String> emails = List.of(EMAIL, "KADI.SORGHO@", "kadi.sorgho", "@example.com", "sorgho");
        for (UUID hospital : java.util.Arrays.asList(hospitalId, null)) {
            for (Boolean active : java.util.Arrays.asList(null, Boolean.TRUE)) {
                for (String typed : dobs) {
                    String key = PatientMatchKeys.birthDateKey(typed);
                    List<UUID> found = ids(patientRepository.searchPatientsExtended(null, null, key, null, null,
                        hospital, active, PageRequest.of(0, 8)));
                    if (!found.equals(List.of(kadidiatou))) {
                        failures.add("dob " + typed + " hospital=" + hospital + " active=" + active + " found " + found);
                    }
                }
                for (String typed : emails) {
                    String pattern = "%" + typed.toLowerCase(java.util.Locale.ROOT) + "%";
                    List<UUID> found = ids(patientRepository.searchPatientsExtended(null, null, null, null, pattern,
                        hospital, active, PageRequest.of(0, 8)));
                    boolean shared = "@example.com".equals(typed);
                    boolean ok = shared ? found.containsAll(List.of(kadidiatou, other)) : found.equals(List.of(kadidiatou));
                    if (!ok) {
                        failures.add("email " + typed + " hospital=" + hospital + " active=" + active + " found " + found);
                    }
                }
            }
        }
        // a date that is not this patient's finds nobody; a non-date stays a filter that matches nothing
        assertThat(patientRepository.searchPatientsExtended(null, null, PatientMatchKeys.birthDateKey("04/11/1993"),
            null, null, hospitalId, null, PageRequest.of(0, 8)).getContent()).isEmpty();
        assertThat(patientRepository.searchPatientsExtended(null, null, PatientMatchKeys.birthDateKey("hier"),
            null, null, hospitalId, null, PageRequest.of(0, 8)).getContent()).isEmpty();
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("QA D1253: every filter combination of the short search runs on PostgreSQL")
    void everyCombinationOfTheShortSearchFindsThePatient() {
        List<String> failures = new ArrayList<>();
        for (int mask = 0; mask < 16; mask++) {
            String name = (mask & 1) == 0 ? null : PatientMatchKeys.nameContainsPattern("Sorgho");
            String email = (mask & 2) == 0 ? null : "%kadi.sorgho%";
            UUID hospital = (mask & 4) == 0 ? null : hospitalId;
            Boolean active = (mask & 8) == 0 ? null : Boolean.TRUE;
            try {
                List<UUID> found = ids(patientRepository.searchPatients(name, email, hospital, active, PageRequest.of(0, 20)));
                if (!found.contains(kadidiatou)) {
                    failures.add("mask " + mask + " missed the patient: " + found);
                }
            } catch (RuntimeException e) {
                failures.add("mask " + mask + " threw " + rootMessage(e));
            }
        }
        assertThat(failures).isEmpty();
    }

    private List<UUID> extended(int mask, Pageable page, boolean wrong) {
        String mrn = set(mask, 0) ? (wrong ? "NOPE-1" : MRN) : null;
        String name = set(mask, 1) ? PatientMatchKeys.nameContainsPattern(wrong ? "Zzzyx" : "Kadidiatou") : null;
        String dob = set(mask, 2) ? (wrong ? "2001-01-01" : BIRTH.toString()) : null;
        String phone = set(mask, 3) ? PatientMatchKeys.phoneContainsPattern(wrong ? "79999999" : "70 26 01 22") : null;
        String email = set(mask, 4) ? (wrong ? "%nobody%" : "%kadi.sorgho%") : null;
        UUID hospital = set(mask, 5) ? hospitalId : null;
        Boolean active = set(mask, 6) ? Boolean.TRUE : null;
        return ids(patientRepository.searchPatientsExtended(mrn, name, dob, phone, email, hospital, active, page));
    }

    private static boolean set(int mask, int filter) {
        return (mask & (1 << filter)) != 0;
    }

    private static String describe(int mask) {
        String[] names = {"mrn", "name", "dob", "phone", "email", "hospitalId", "active"};
        List<String> set = new ArrayList<>();
        for (int i = 0; i < FILTERS; i++) {
            if (set(mask, i)) {
                set.add(names[i]);
            }
        }
        return "filters " + set;
    }

    private static List<UUID> ids(Page<Patient> page) {
        return page.getContent().stream().map(Patient::getId).toList();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage()).lines().findFirst().orElse("");
    }

    private UUID patient(Hospital hospital, String first, String last, String mrn, String phone, String email,
                         LocalDate birth) {
        User user = userRepository.save(User.builder()
            .username("d1253-" + mrn.toLowerCase()).passwordHash("hashed-secret").email(email)
            .phoneNumber(phone).firstName(first).lastName(last).build());
        Patient p = patientRepository.save(Patient.builder()
            .user(user)
            .firstName(first).lastName(last)
            .dateOfBirth(birth)
            .phoneNumberPrimary(phone)
            .email(email)
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
