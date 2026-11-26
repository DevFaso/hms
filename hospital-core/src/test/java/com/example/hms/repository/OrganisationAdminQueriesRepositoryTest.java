package com.example.hms.repository;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.specification.UserRoleHospitalAssignmentSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform admin rules 2026-11-20, rules 1a, 1b and 3, on a real database: the
 * queries that decide an organisation admin's reach and totals, and the
 * currency lock. Organisation 1 owns hospitals A and B and a pharmacy;
 * organisation 2 owns hospital C; hospital L belongs to no organisation.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("organisation admin queries (platform admin rules 1a, 1b, 3)")
class OrganisationAdminQueriesRepositoryTest {

    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private OrganisationTotalsRepository totals;
    @Autowired private HospitalRepository hospitals;
    @Autowired private TestEntityManager em;

    private Hospital hospitalA;
    private Hospital hospitalB;
    private Hospital pharmacy;
    private Hospital hospitalC;
    private Hospital lone;
    private Role nurse;
    private Role patient;
    private int seq;

    @BeforeEach
    void setUp() {
        Organization one = organisation("ORG-ONE");
        Organization two = organisation("ORG-TWO");
        hospitalA = hospital(one, "HORGA", FacilityType.HOSPITAL);
        hospitalB = hospital(one, "HORGB", FacilityType.HOSPITAL);
        pharmacy = hospital(one, "PORGA", FacilityType.PHARMACY);
        hospitalC = hospital(two, "HORGC", FacilityType.HOSPITAL);
        lone = hospital(null, "HLONE", FacilityType.HOSPITAL);
        nurse = em.persist(Role.builder().name("Nurse").code("ROLE_NURSE").description("Nurse").build());
        patient = em.persist(Role.builder().name("Patient").code("ROLE_PATIENT").description("Patient").build());
        em.flush();
    }

    private Organization organisation(String code) {
        return em.persist(Organization.builder()
            .name("Network " + code).code(code).type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
    }

    private Hospital hospital(Organization organization, String code, FacilityType type) {
        seq++;
        Hospital hospital = Hospital.builder()
            .name("Facility " + code).code(code)
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+2265557" + seq)
            .email(code.toLowerCase() + "@facility.test")
            .organization(organization).build();
        hospital.setFacilityType(type);
        return em.persist(hospital);
    }

    private UserRoleHospitalAssignment assign(String name, Hospital hospital, Role role) {
        seq++;
        User user = em.persist(User.builder()
            .username(name).passwordHash("hashed-password")
            .email(name + "@organisation.test").firstName(name).lastName("User")
            .phoneNumber("+2267900" + seq).isActive(true).isDeleted(false).build());
        return em.persist(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-ORG" + seq).description("assignment")
            .user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
    }

    // ── rule 1a: the scope ──────────────────────────────────────────────────

    @Test
    @DisplayName("the hospitals of an organisation: its clinical hospitals, never its pharmacy, never another organisation's")
    void organisationScope() {
        assertThat(assignments.findClinicalHospitalIdsInOrganisationsOf(Set.of(hospitalA.getId())))
            .containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId());
        assertThat(assignments.findClinicalHospitalIdsInOrganisationsOf(Set.of(hospitalC.getId())))
            .containsExactly(hospitalC.getId());
        // A hospital with no organisation is alone: itself, and not even the other lone hospitals.
        hospital(null, "HLON2", FacilityType.HOSPITAL);
        em.flush();
        assertThat(assignments.findClinicalHospitalIdsInOrganisationsOf(Set.of(lone.getId())))
            .containsExactly(lone.getId());
        // An assignment held at the organisation's pharmacy opens nothing: not the pharmacy, not A or B.
        assertThat(assignments.findClinicalHospitalIdsInOrganisationsOf(Set.of(pharmacy.getId()))).isEmpty();
        // Two organisations at once (an admin of both): each one's clinical hospitals, nothing more.
        assertThat(assignments.findClinicalHospitalIdsInOrganisationsOf(Set.of(hospitalB.getId(), hospitalC.getId())))
            .containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId(), hospitalC.getId());
    }

    @Test
    @DisplayName("the list of an organisation admin: staff rows at its hospitals; no patient row, no other organisation's row")
    void organisationListing() {
        assign("nurse-a", hospitalA, nurse);
        assign("nurse-b", hospitalB, nurse);
        assign("nurse-c", hospitalC, nurse);
        assign("patient-b", hospitalB, patient);
        em.flush();
        Set<java.util.UUID> organisation = Set.of(hospitalA.getId(), hospitalB.getId());

        Specification<UserRoleHospitalAssignment> listing = UserRoleHospitalAssignmentSpecification
            .belongsToAnyHospital(organisation)
            .and(UserRoleHospitalAssignmentSpecification.staffRowsOnly());
        assertThat(assignments.findAll(listing))
            .extracting(a -> a.getUser().getUsername())
            .containsExactlyInAnyOrder("nurse-a", "nurse-b");

        // An empty set is "nothing", never "no filter".
        assertThat(assignments.findAll(UserRoleHospitalAssignmentSpecification.belongsToAnyHospital(Set.of())))
            .isEmpty();
    }

    // ── rule 1b: the totals ─────────────────────────────────────────────────

    @Test
    @DisplayName("the totals read one hospital each, and only clinical hospitals are listed")
    void totalsQueriesRun() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 20);

        List<Hospital> listed = totals.findClinicalByIdIn(
            Set.of(hospitalA.getId(), hospitalB.getId(), pharmacy.getId()));
        assertThat(listed).extracting(Hospital::getId)
            .containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId());
        // A hospital with nothing recorded reads zero on every figure (never null).
        assertThat(totals.countAppointments(hospitalA.getId(), from, to)).isZero();
        assertThat(totals.sumInvoiced(hospitalA.getId(), from, to)).isEqualByComparingTo("0");
        assertThat(totals.sumCollected(hospitalA.getId(), from, to)).isEqualByComparingTo("0");
        // QA D3981: the refunds paid out, taken off what was collected.
        assertThat(totals.sumRefundedOut(hospitalA.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
            .isEqualByComparingTo("0");
        assertThat(totals.countDeaths(hospitalA.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
            .isZero();
    }

    // ── rule 3: the currency lock ───────────────────────────────────────────

    @Test
    @DisplayName("a hospital with no invoice and no price is not locked; one price locks that hospital and no other")
    void currencyLockFollowsTheFirstPrice() {
        assertThat(hospitals.hasPriceListItem(hospitalA.getId())).isFalse();
        assertThat(hospitals.hasInvoice(hospitalA.getId())).isFalse();

        em.persist(com.example.hms.model.PriceListItem.builder()
            .hospitalId(hospitalA.getId()).code("CONS-GEN").label("Consultation")
            .category(com.example.hms.enums.PriceListCategory.CONSULTATION).build());
        em.flush();

        assertThat(hospitals.hasPriceListItem(hospitalA.getId())).isTrue();
        assertThat(hospitals.hasPriceListItem(hospitalB.getId())).isFalse();
    }
}
