package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InBasketItemStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Announcement;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Hospital;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round r81, platform lane: the queries behind D4301, D4307, D4308, D4309
 * and D4320, against a database - each with the staff or the rows of two
 * hospitals, since every one of these defects was about who sees what.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("r81 platform lane: audience and attribution queries")
class R81PlatformQueriesRepositoryTest {

    @Autowired private AnnouncementRepository announcements;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private UserRepository users;
    @Autowired private InBasketItemRepository inBasket;
    @Autowired private IntegrationMessageEventRepository messages;
    @Autowired private AuditEventLogRepository auditLogs;
    @Autowired private TestEntityManager em;

    private Hospital hospitalA;
    private Hospital hospitalB;
    private Role nurse;
    private Role doctor;
    private Role patient;
    private Role superAdmin;
    private int seq;

    @BeforeEach
    void setUp() {
        Organization organization = em.persist(Organization.builder()
            .name("R81 Network").code("ORG-R81").type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospitalA = hospital(organization, "HR81A", "+22655571");
        hospitalB = hospital(organization, "HR81B", "+22655572");
        nurse = em.persist(Role.builder().name("Nurse").code("ROLE_NURSE").description("Nurse").build());
        doctor = em.persist(Role.builder().name("Doctor").code("ROLE_DOCTOR").description("Doctor").build());
        patient = em.persist(Role.builder().name("Patient").code("ROLE_PATIENT").description("Patient").build());
        superAdmin = em.persist(Role.builder().name("Super admin").code("ROLE_SUPER_ADMIN")
            .description("Platform administrator").build());
    }

    private Hospital hospital(Organization organization, String code, String phone) {
        return em.persist(Hospital.builder()
            .name("Hospital " + code).code(code)
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber(phone)
            .email(code.toLowerCase() + "@hospital.test")
            .organization(organization).build());
    }

    private User user(String name, boolean active) {
        seq++;
        return em.persist(User.builder()
            .username(name).passwordHash("hashed-password")
            .email(name + "@r81.test").firstName(name).lastName("User")
            .phoneNumber("+2267810" + seq).isActive(active).isDeleted(false).build());
    }

    private UserRoleHospitalAssignment assign(User user, Hospital hospital, Role role, boolean active) {
        seq++;
        return em.persist(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-R81" + seq).description("assignment")
            .user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(active).build());
    }

    // ── D4309 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("D4309: a platform announcement is read at both hospitals; a hospital's own only there")
    void aPlatformAnnouncementIsReadAtEveryHospital() {
        em.persist(Announcement.builder().text("Maintenance de la plateforme samedi")
            .date(LocalDateTime.now().minusMinutes(5)).hospital(null).build());
        em.persist(Announcement.builder().text("Réunion du personnel de A")
            .date(LocalDateTime.now()).hospital(hospitalA).build());
        em.flush();

        assertThat(announcements.findVisibleAtHospital(hospitalA.getId(), PageRequest.of(0, 10)))
            .extracting(Announcement::getText)
            .containsExactly("Réunion du personnel de A", "Maintenance de la plateforme samedi");
        assertThat(announcements.findVisibleAtHospital(hospitalB.getId(), PageRequest.of(0, 10)))
            .extracting(Announcement::getText)
            .containsExactly("Maintenance de la plateforme samedi");
        assertThat(announcements.countVisibleAtHospital(hospitalA.getId())).isEqualTo(2);
        assertThat(announcements.countVisibleAtHospital(hospitalB.getId())).isEqualTo(1);
    }

    // ── D4320 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("D4320: two people are colleagues only through an active staff role at one same hospital")
    void colleaguesShareAnActiveHospital() {
        User nurseA = user("nurse-a", true);
        assign(nurseA, hospitalA, nurse, true);
        User doctorA = user("doctor-a", true);
        assign(doctorA, hospitalA, doctor, true);
        User doctorB = user("doctor-b", true);
        assign(doctorB, hospitalB, doctor, true);
        User formerA = user("former-a", true);
        assign(formerA, hospitalA, doctor, false);
        User patientA = user("patient-a", true);
        assign(patientA, hospitalA, patient, true);
        // A platform administrator holds the role at no hospital.
        User platformAdmin = user("platform-admin", true);
        assign(platformAdmin, null, superAdmin, true);
        em.flush();

        assertThat(assignments.sharesActiveHospital(nurseA.getId(), doctorA.getId())).isTrue();
        assertThat(assignments.sharesActiveHospital(doctorA.getId(), nurseA.getId())).isTrue();
        assertThat(assignments.sharesActiveHospital(nurseA.getId(), doctorB.getId())).isFalse();
        assertThat(assignments.sharesActiveHospital(nurseA.getId(), formerA.getId())).isFalse();
        assertThat(assignments.sharesActiveHospital(nurseA.getId(), patientA.getId())).isFalse();
        assertThat(assignments.sharesActiveHospital(nurseA.getId(), platformAdmin.getId())).isFalse();
        assertThat(assignments.sharesActiveHospital(nurseA.getId(), UUID.randomUUID())).isFalse();
    }

    // ── D4308 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("D4308: platform items go to the active platform administrators and to nobody else")
    void platformAdministratorsAreTheRecipients() {
        User admin = user("platform-admin", true);
        assign(admin, null, superAdmin, true);
        User disabledAdmin = user("disabled-admin", false);
        assign(disabledAdmin, null, superAdmin, true);
        User formerAdmin = user("former-admin", true);
        assign(formerAdmin, null, superAdmin, false);
        // Revoked by deactivating the assignment: the account keeps its role link and another role.
        em.persist(com.example.hms.model.UserRole.builder()
            .id(new com.example.hms.model.UserRoleId(formerAdmin.getId(), superAdmin.getId()))
            .user(formerAdmin).role(superAdmin).build());
        assign(formerAdmin, hospitalA, nurse, true);
        assign(user("nurse-a", true), hospitalA, nurse, true);
        em.flush();

        assertThat(users.findActivePlatformAdministrators())
            .extracting(User::getUsername).containsExactly("platform-admin");
    }

    @Test
    @DisplayName("D4308: an item that names no hospital is stored and read, whatever hospital is selected")
    void aPlatformItemIsReadWithOrWithoutAHospitalSelected() {
        User admin = user("platform-admin", true);
        User nurseA = user("nurse-a", true);
        em.persist(InBasketItem.builder().recipientUser(admin).hospital(null)
            .itemType(InBasketItemType.TASK).title("Prestataire à vérifier").build());
        em.persist(InBasketItem.builder().recipientUser(admin).hospital(hospitalA)
            .itemType(InBasketItemType.MESSAGE).title("Webhook désactivé").build());
        em.persist(InBasketItem.builder().recipientUser(nurseA).hospital(hospitalA)
            .itemType(InBasketItemType.MESSAGE).title("Pour l'infirmière").build());
        em.flush();

        assertThat(inBasket.findByRecipientFiltered(admin.getId(), null, null, null, PageRequest.of(0, 10))
            .getContent()).extracting(InBasketItem::getTitle)
            .containsExactlyInAnyOrder("Prestataire à vérifier", "Webhook désactivé");
        assertThat(inBasket.findByRecipientFiltered(admin.getId(), hospitalB.getId(), null, null,
            PageRequest.of(0, 10)).getContent()).extracting(InBasketItem::getTitle)
            .containsExactly("Prestataire à vérifier");
        assertThat(inBasket.countByRecipientForSummary(admin.getId(), null, InBasketItemStatus.UNREAD)).isEqualTo(2);
        // Another reader's inbox is untouched by a platform item.
        assertThat(inBasket.findByRecipientFiltered(nurseA.getId(), hospitalA.getId(), null, null,
            PageRequest.of(0, 10)).getContent()).extracting(InBasketItem::getTitle)
            .containsExactly("Pour l'infirmière");
    }

    // ── D4301 ───────────────────────────────────────────────────────────

    private IntegrationMessageEvent message(String correlation, IntegrationMessageStatus status, LocalDateTime at) {
        return em.persist(IntegrationMessageEvent.builder()
            .integrationId("partner.nhis").direction(IntegrationMessageDirection.OUTBOUND)
            .messageType("PROBE").correlationId(correlation).payload("{\"stub\":true}")
            .status(status).attemptCount(1).lastAttemptedAt(at).receivedAt(at).build());
    }

    @Test
    @DisplayName("D4301: the rows marked as handled and the banner's count follow one rule")
    void resolvedFailuresAndTheBannerAgree() {
        // Milliseconds: what a database column keeps of a timestamp.
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        IntegrationMessageEvent replayed = message("corr-1", IntegrationMessageStatus.FAILED, now.minusHours(2));
        message("corr-1", IntegrationMessageStatus.REPLAYED, now.minusHours(1));
        IntegrationMessageEvent waiting = message("corr-2", IntegrationMessageStatus.FAILED, now.minusHours(2));
        IntegrationMessageEvent sent = message("corr-3", IntegrationMessageStatus.SENT, now.minusHours(2));
        em.flush();

        List<UUID> resolved = messages.findResolvedFailureIds(
            List.of(replayed.getId(), waiting.getId(), sent.getId()));

        assertThat(resolved).containsExactly(replayed.getId());
        // 2 failed rows, 1 handled: the banner says 1, and 1 row still offers a replay.
        assertThat(messages.countUnresolvedDeadLetters()).isEqualTo(2L - resolved.size());
        assertThat(messages.existsLaterAttempt("corr-1", replayed.getLastAttemptedAt())).isTrue();
        assertThat(messages.existsLaterAttempt("corr-2", waiting.getLastAttemptedAt())).isFalse();
        assertThat(messages.findTop50ByCorrelationIdOrderByReceivedAtAsc("corr-1"))
            .extracting(IntegrationMessageEvent::getStatus)
            .containsExactly(IntegrationMessageStatus.FAILED, IntegrationMessageStatus.REPLAYED);
    }

    // ── D4307 ───────────────────────────────────────────────────────────

    private void audit(Hospital stamped, UserRoleHospitalAssignment assignment, LocalDateTime at) {
        AuditEventLog log = new AuditEventLog();
        log.setEventType(AuditEventType.DATA_CREATE);
        log.setEventDescription("POST /something");
        log.setEventTimestamp(at);
        log.setHospitalId(stamped == null ? null : stamped.getId());
        if (assignment != null) {
            log.setAssignment(assignment);
            log.setUser(assignment.getUser());
        }
        em.persist(log);
    }

    @Test
    @DisplayName("D4307: each event is counted once, for the hospital stamped on it or its assignment's")
    void eventsAreAttributedToOneHospitalEach() {
        UserRoleHospitalAssignment nurseAtA = assign(user("nurse-a", true), hospitalA, nurse, true);
        UserRoleHospitalAssignment doctorAtB = assign(user("doctor-b", true), hospitalB, doctor, true);
        LocalDateTime now = LocalDateTime.now();
        audit(null, nurseAtA, now);          // stamped from its assignment
        audit(null, nurseAtA, now);
        audit(hospitalA, null, now);         // a system row acting on A's data
        audit(null, doctorAtB, now);
        audit(null, null, now);              // a platform-wide event: nobody's
        em.flush();

        // The event time is the time of writing, so the period is the last day up to now.
        List<Object[]> rows = auditLogs.countAttributedToHospitalBetween(now.minusDays(1), now.plusMinutes(5));
        assertThat(auditLogs.countAttributedToHospitalBetween(now.minusDays(40), now.minusDays(10))).isEmpty();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo(hospitalA.getId());
        assertThat(rows.get(0)[1]).isEqualTo(hospitalA.getName());
        assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(3L);
        assertThat(rows.get(1)[0]).isEqualTo(hospitalB.getId());
        assertThat(((Number) rows.get(1)[2]).longValue()).isEqualTo(1L);
    }
}
