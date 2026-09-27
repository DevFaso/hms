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
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TenantScopedDataJpaTest
class PatientRepositoryTenantScopeTest {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void setUp() {
        HospitalContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    void searchPatientsExtendedIncludesRegistrationHospitalInTenantScope() {
        Organization organization = entityManager.persist(HospitalFixtures.organization("Org One", "ORG-ONE"));
        Hospital primaryHospital = entityManager.persist(
            HospitalFixtures.hospital(organization, "Primary Hospital", "PRIM-HOSP"));
        Hospital scopedHospital = entityManager.persist(
            HospitalFixtures.hospital(organization, "Scoped Hospital", "SCOP-HOSP"));

        User user = User.builder()
            .username("patient-user")
            .passwordHash("hashed-secret")
            .email("yacouba@example.com")
            .phoneNumber("+22670000000")
            .firstName("Yacouba")
            .lastName("Diallo")
            .build();
        user = entityManager.persist(user);

        Patient patient = Patient.builder()
            .firstName("Yacouba")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1990, 1, 1))
            .gender("MALE")
            .address("123 Primary Way")
            .phoneNumberPrimary("+22670000000")
            .email("yacouba@example.com")
            .user(user)
            .hospitalId(primaryHospital.getId())
            .organizationId(organization.getId())
            .active(true)
            .build();
        patient = entityManager.persist(patient);

        PatientHospitalRegistration registration = PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(scopedHospital)
            .mrn("MRN-123")
            .registrationDate(LocalDate.now())
            .active(true)
            .build();
        entityManager.persist(registration);

        entityManager.flush();
        entityManager.clear();

        HospitalContext context = HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .principalUsername("dev_doctor")
            .activeHospitalId(scopedHospital.getId())
            .permittedHospitalIds(Set.of(scopedHospital.getId()))
            .permittedOrganizationIds(Collections.emptySet())
            .permittedDepartmentIds(Collections.emptySet())
            .superAdmin(false)
            .hospitalAdmin(false)
            .build();
        HospitalContextHolder.setContext(context);

        Page<Patient> result = patientRepository.searchPatientsExtended(
            null,
            "%yacouba%",
            null,
            null,
            null,
            scopedHospital.getId(),
            true,
            PageRequest.of(0, 5)
        );

        assertThat(result.getContent()).extracting(Patient::getId)
            .containsExactly(patient.getId());
    }
}
