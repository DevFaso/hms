package com.example.hms.repository;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hospital-scope picker's query. An empty query must list active
 * hospitals by name so the picker has something to show on open; a prefix
 * narrows on the start of the name (the V90 index on LOWER(name) serves
 * that, and nothing indexes the code); archived tenants are never offered.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TenantContextAccessor.class, EncryptionKeyHolder.class})
class HospitalRepositorySearchIT {

    @Autowired
    private HospitalRepository hospitalRepository;
    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void seed() {
        Organization organization = em.persist(Organization.builder()
            .name("Scope Org").code("ORG-SC1").type(OrganizationType.HOSPITAL_CHAIN).build());
        em.persist(hospital("Memorial Hospital", "MEM-01", true, organization));
        em.persist(hospital("Central Clinic", "HCX-02", true, organization));
        em.persist(hospital("Archived Place", "ARC-03", false, organization));
        em.flush();
        em.clear();
    }

    @Test
    void emptyQueryListsActiveHospitalsByName() {
        var page = hospitalRepository.searchHospitals(null, null, null, Boolean.TRUE, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Hospital::getName)
            .containsExactly("Central Clinic", "Memorial Hospital");
    }

    @Test
    void prefixNarrowsOnTheStartOfTheName() {
        assertThat(hospitalRepository.searchHospitals("mem", null, null, Boolean.TRUE, PageRequest.of(0, 20)).getContent())
            .extracting(Hospital::getName).containsExactly("Memorial Hospital");
    }

    @Test
    void matchIsAPrefixNotASubstringSoTheNameIndexStaysUsable() {
        assertThat(hospitalRepository.searchHospitals("clinic", null, null, Boolean.TRUE, PageRequest.of(0, 20)).getContent())
            .isEmpty();
    }

    private static Hospital hospital(String name, String code, boolean active, Organization organization) {
        return Hospital.builder()
            .name(name).code(code).active(active)
            .address("1 Rue").city("Ouagadougou").country("BF")
            .organization(organization)
            .build();
    }
}
