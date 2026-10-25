package com.example.hms.repository;

import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.service.impl.SuperAdminAuditSearchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D786 and D794 on the real JPA layer (H2).
 *
 * <p>D786: the two V229 cancellation rows (SYSTEM actor "Migration V229", no
 * user, no assignment, hospital name set) were visible to the super-admin and
 * to nobody at the hospital whose invoices were cancelled: every
 * hospital-scoped read inner-joined the assignment. Such a row now carries its
 * hospital (V235 backfills the V229 rows), and every hospital-scoped read
 * (the audit page, the event-type counts, the per-hospital page and the
 * admin dashboard, the super-admin hospital/organization filter) finds it.
 *
 * <p>D794: the audit page searched the 20 rows on screen. The search is now a
 * database predicate, so a match on any page is found.
 */
@TenantScopedDataJpaTest
class AuditEventLogHospitalAnchorIT {

    private static final PageRequest FIRST_PAGE =
        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "eventTimestamp"));

    @Autowired
    private AuditEventLogRepository repository;
    @Autowired
    private TenantExportRepository exportRepository;
    @Autowired
    private TestEntityManager em;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private User adminA;
    private UserRoleHospitalAssignment adminSeatA;
    private AuditEventLog v229RowA;
    private AuditEventLog v229RowB;
    private AuditEventLog platformRow;
    private AuditEventLog userRowA;

    @BeforeEach
    void seed() {
        organization = em.persist(HospitalFixtures.organization("Anchor Org", "ORG-AN1"));
        Organization otherOrganization = em.persist(HospitalFixtures.organization("Other Org", "ORG-AN2"));
        hospitalA = em.persist(HospitalFixtures.hospital(organization, "Anchor Hospital A", "HOSP-AN1"));
        hospitalB = em.persist(HospitalFixtures.hospital(otherOrganization, "Anchor Hospital B", "HOSP-AN2"));
        adminA = em.persist(User.builder()
            .username("an_admin_a").passwordHash("h").email("an_admin_a@example.com")
            .phoneNumber("+22670000931").firstName("Awa").lastName("Admin").build());
        Role role = em.persist(Role.builder().name("ROLE_AN_ADMIN").code("ROLE_AN_ADMIN").build());
        adminSeatA = em.persist(UserRoleHospitalAssignment.builder()
            .user(adminA).role(role).hospital(hospitalA).assignedAt(LocalDateTime.now()).active(true).build());

        v229RowA = em.persist(systemRow(hospitalA, "Anchor Hospital A"));
        v229RowB = em.persist(systemRow(hospitalB, "Anchor Hospital B"));
        platformRow = em.persist(AuditEventLog.builder()
            .eventType(AuditEventType.LOGIN_FAILURE)
            .eventDescription("Platform-wide event")
            .status(AuditStatus.SUCCESS)
            .actorType(ActorType.SYSTEM)
            .actorLabel("SYSTEM")
            .build());
        userRowA = em.persist(AuditEventLog.builder()
            .eventType(AuditEventType.USER_CREATE)
            .eventDescription("New user account created")
            .status(AuditStatus.SUCCESS)
            .actorType(ActorType.USER)
            .user(adminA)
            .assignment(adminSeatA)
            .resourceName("Multi91064 Qa")
            .build());
        em.flush();
        em.clear();
    }

    /** The row V229 wrote, as V235 leaves it: no user, no assignment, its hospital set. */
    private static AuditEventLog systemRow(Hospital hospital, String hospitalName) {
        return AuditEventLog.builder()
            .eventType(AuditEventType.INVOICE_UPDATED)
            .eventDescription("Zero-total invoice cancelled by migration V229 (QA D783)")
            .details("status -> CANCELLED; total 0, no payment row")
            .status(AuditStatus.SUCCESS)
            .actorType(ActorType.SYSTEM)
            .actorLabel("Migration V229")
            .entityType("BillingInvoice")
            .resourceId(UUID.randomUUID().toString())
            .hospitalName(hospitalName)
            .hospitalId(hospital.getId())
            .build();
    }

    private static Set<UUID> ids(Page<AuditEventLog> page) {
        return page.getContent().stream().map(AuditEventLog::getId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("D786: the hospital audit page (GET /audit-logs, hospital admin) shows its own SYSTEM rows")
    void theHospitalScopedPageShowsTheHospitalsSystemRows() {
        Page<AuditEventLog> page = repository.findAll(
            AuditEventLogSpecifications.anchoredAt(Set.of(hospitalA.getId())), FIRST_PAGE);

        assertThat(ids(page)).containsExactlyInAnyOrder(v229RowA.getId(), userRowA.getId());
        assertThat(ids(page)).doesNotContain(v229RowB.getId(), platformRow.getId());
    }

    @Test
    @DisplayName("D786: the INVOICE_UPDATED filter on the hospital page finds the V229 row")
    void theEventTypeFilterOnTheHospitalPageFindsTheSystemRow() {
        Page<AuditEventLog> page = repository.findAll(
            AuditEventLogSpecifications.anchoredAt(Set.of(hospitalA.getId()))
                .and(AuditEventLogSpecifications.ofType(AuditEventType.INVOICE_UPDATED)), FIRST_PAGE);

        assertThat(ids(page)).containsExactly(v229RowA.getId());
    }

    @Test
    @DisplayName("D786: GET /audit-logs/hospital/{id} and the admin dashboard read the SYSTEM row too")
    void thePerHospitalQueryShowsTheSystemRow() {
        Page<AuditEventLog> page = repository.findAnchoredAtHospital(hospitalA.getId(), PageRequest.of(0, 20));

        assertThat(ids(page)).containsExactlyInAnyOrder(v229RowA.getId(), userRowA.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        // countDailyByHospital (the dashboard's 7-day chart) shares this WHERE;
        // it uses Postgres' date() and cannot run on H2.
    }

    @Test
    @DisplayName("D786: the event-type counts (the page's dropdown) count the SYSTEM row")
    void theEventTypeCountsIncludeTheSystemRow() {
        Map<String, Long> counts = repository.countByEventTypeForHospitals(Set.of(hospitalA.getId())).stream()
            .collect(Collectors.toMap(row -> row[0].toString(), row -> ((Number) row[1]).longValue()));

        assertThat(counts).containsEntry("INVOICE_UPDATED", 1L).containsEntry("USER_CREATE", 1L).hasSize(2);
    }

    @Test
    @DisplayName("D786: the hospital's tenant export carries its SYSTEM rows, never another hospital's or a platform row")
    void theTenantExportCarriesTheHospitalsSystemRows() {
        Set<UUID> exported = exportRepository.exportAuditEvents(Set.of(hospitalA.getId()), PageRequest.of(0, 50))
            .stream().map(row -> (UUID) row[0]).collect(Collectors.toSet());

        assertThat(exported).containsExactlyInAnyOrder(v229RowA.getId(), userRowA.getId());
    }

    @Test
    @DisplayName("D786: a row with an assignment carries the assignment's hospital")
    void aRowWithAnAssignmentIsStampedWithItsHospital() {
        assertThat(repository.findById(userRowA.getId()).orElseThrow().getHospitalId())
            .isEqualTo(hospitalA.getId());
        assertThat(repository.findById(platformRow.getId()).orElseThrow().getHospitalId()).isNull();
    }

    @Test
    @DisplayName("D786: the super-admin audit search's hospital and organization filters find the SYSTEM row")
    void theSuperAdminHospitalAndOrganizationFiltersFindTheSystemRow() {
        SuperAdminAuditSearchServiceImpl search =
            new SuperAdminAuditSearchServiceImpl(repository, Mockito.mock(AuditEventLogMapper.class));

        AuditSearchPageDTO byHospital = search.search(new AuditSearchFilter(null, null,
            List.of(AuditEventType.INVOICE_UPDATED), null, hospitalA.getId(), null, null, null, null, null, null,
            null), PageRequest.of(0, 20));
        AuditSearchPageDTO byOrganization = search.search(new AuditSearchFilter(null, null,
            List.of(AuditEventType.INVOICE_UPDATED), null, null, organization.getId(), null, null, null, null, null,
            null), PageRequest.of(0, 20));

        assertThat(byHospital.getTotalElements()).isEqualTo(1);
        assertThat(byOrganization.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("D794: the search finds a row that is not on the first page")
    void theSearchRunsOverEveryRowNotTheCurrentPage() {
        for (int i = 0; i < 25; i++) {
            em.persist(AuditEventLog.builder()
                .eventType(AuditEventType.DATA_UPDATE)
                .eventDescription("PUT /patients/{patientId}")
                .status(AuditStatus.SUCCESS)
                .actorType(ActorType.USER)
                .user(em.find(User.class, adminA.getId()))
                .assignment(em.find(UserRoleHospitalAssignment.class, adminSeatA.getId()))
                .build());
        }
        em.flush();
        em.clear();
        Set<UUID> scope = Set.of(hospitalA.getId());

        Page<AuditEventLog> unfiltered = repository.findAll(AuditEventLogSpecifications.anchoredAt(scope), FIRST_PAGE);
        assertThat(ids(unfiltered)).as("the onboarding row is not on the first page").doesNotContain(userRowA.getId());

        Page<AuditEventLog> searched = repository.findAll(AuditEventLogSpecifications.anchoredAt(scope)
            .and(AuditEventLogSpecifications.matching("multi91064")), FIRST_PAGE);
        assertThat(ids(searched)).containsExactly(userRowA.getId());

        Page<AuditEventLog> byActor = repository.findAll(AuditEventLogSpecifications.anchoredAt(scope)
            .and(AuditEventLogSpecifications.matching("an_admin_a")), FIRST_PAGE);
        assertThat(byActor.getTotalElements()).as("the actor's sign-in name matches").isEqualTo(26);
    }

    @Test
    @DisplayName("D794: a LIKE wildcard in the search is a literal character, never 'match everything'")
    void aWildcardInTheSearchIsLiteral() {
        Page<AuditEventLog> percent = repository.findAll(AuditEventLogSpecifications.everyRow()
            .and(AuditEventLogSpecifications.matching("%")), FIRST_PAGE);
        Page<AuditEventLog> underscore = repository.findAll(AuditEventLogSpecifications.everyRow()
            .and(AuditEventLogSpecifications.matching("_")), FIRST_PAGE);

        assertThat(percent.getTotalElements()).isZero();
        // Only the user row matches "_": its actor's sign-in name is an_admin_a.
        assertThat(ids(underscore)).containsExactly(userRowA.getId());
    }
}
