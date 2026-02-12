package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(TenantContextAccessor.class)
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
        Organization organization = Organization.builder()
            .name("Org One")
            .code("ORG-ONE")
            .type(OrganizationType.HOSPITAL_CHAIN)
            .build();
        organization = entityManager.persist(organization);

        Hospital primaryHospital = Hospital.builder()
            .name("Primary Hospital")
            .code("PRIM-HOSP")
            .address("123 Primary Way")
            .city("Ouagadougou")
            .country("BF")
            .organization(organization)
            .build();
        primaryHospital = entityManager.persist(primaryHospital);

        Hospital scopedHospital = Hospital.builder()
            .name("Scoped Hospital")
            .code("SCOP-HOSP")
            .address("456 Scoped Blvd")
            .city("Ouagadougou")
            .country("BF")
            .organization(organization)
            .build();
        scopedHospital = entityManager.persist(scopedHospital);

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
