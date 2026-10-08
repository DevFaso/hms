package com.example.hms.repository;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider plan AC-11, item 1: the finders used for lists, search, counts and
 * KPIs keep HOSPITAL rows only. Each is asked with an active pharmacy and an
 * active laboratory present, in the same organisation as the hospital.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("HospitalRepository clinical finders")
class HospitalRepositoryClinicalFindersTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private TestEntityManager entityManager;

    private Organization organization;
    private Hospital hospital;
    private Hospital pharmacy;
    private Hospital laboratory;

    @BeforeEach
    void setUp() {
        organization = entityManager.persist(Organization.builder()
            .name("Clinical Finders Org")
            .code("ORG-" + suffix())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = facility("Alpha Hospital", FacilityType.HOSPITAL, organization);
        pharmacy = facility("Alpha Pharmacy", FacilityType.PHARMACY, organization);
        laboratory = facility("Alpha Laboratory", FacilityType.LABORATORY, organization);
        entityManager.flush();
    }

    private Hospital facility(String name, FacilityType type, Organization org) {
        String n = suffix();
        Hospital row = Hospital.builder()
            .name(name + " " + n)
            .code("CF" + n)
            .city("Ouagadougou")
            .state("Kadiogo")
            .country("Burkina Faso")
            .address("1 Main St")
            .email("cf" + n.toLowerCase() + "@facility.test")
            .active(true)
            .build();
        row.setFacilityType(type);
        row.setOrganization(org);
        return entityManager.persist(row);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Set<UUID> ids(List<Hospital> rows) {
        return rows.stream().map(Hospital::getId).collect(java.util.stream.Collectors.toSet());
    }

    private void assertClinicalOnly(List<Hospital> rows) {
        assertThat(ids(rows)).contains(hospital.getId()).doesNotContain(pharmacy.getId(), laboratory.getId());
    }

    @Test
    @DisplayName("lists and search: findAllHospitals, findAllForFilters, searchHospitals, findAllWithDepartments, by organisation")
    void listsKeepHospitalsOnly() {
        assertClinicalOnly(hospitalRepository.findAllHospitals());
        assertClinicalOnly(hospitalRepository.findAllForFilters(null, null, null, null));
        assertClinicalOnly(hospitalRepository.findAllForFilters(organization.getId(), null, null, null));
        assertClinicalOnly(hospitalRepository.searchHospitals("Alpha", null, null, null, PageRequest.of(0, 50)).getContent());
        assertClinicalOnly(hospitalRepository.findAllWithDepartments("Alpha", null));
        assertClinicalOnly(hospitalRepository.findByOrganizationIdOrderByNameAsc(organization.getId()));
    }

    @Test
    @DisplayName("counts: countHospitals and countActiveHospitals leave the providers out")
    void countsKeepHospitalsOnly() {
        long all = hospitalRepository.count();
        assertThat(hospitalRepository.countHospitals()).isEqualTo(all - 2);
        assertThat(hospitalRepository.countActiveHospitals()).isLessThanOrEqualTo(all - 2);
        long activeHospitals = hospitalRepository.findAllHospitals().stream().filter(Hospital::isActive).count();
        assertThat(hospitalRepository.countActiveHospitals()).isEqualTo(activeHospitals);
    }

    @Test
    @DisplayName("the boot jobs' finder skips a provider with no organisation")
    void organisationlessProviderIsNotABootJobInput() {
        Hospital loneHospital = facility("Lone Hospital", FacilityType.HOSPITAL, null);
        Hospital lonePharmacy = facility("Lone Pharmacy", FacilityType.PHARMACY, null);
        entityManager.flush();
        assertThat(ids(hospitalRepository.findByOrganizationIsNull()))
            .contains(loneHospital.getId())
            .doesNotContain(lonePharmacy.getId());
    }

    @Test
    @DisplayName("the confinement lookup names the provider types among a set of ids")
    void providerTypesOfASet() {
        assertThat(hospitalRepository.findProviderFacilityTypesByIdIn(
            List.of(hospital.getId(), pharmacy.getId(), laboratory.getId())))
            .containsExactlyInAnyOrder(FacilityType.PHARMACY, FacilityType.LABORATORY);
        assertThat(hospitalRepository.findProviderFacilityTypesByIdIn(List.of(hospital.getId()))).isEmpty();
    }
}
