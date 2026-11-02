package com.example.hms.repository;

import com.example.hms.fhir.provider.MedicationRequestFhirResourceProvider;
import com.example.hms.fhir.provider.ObservationFhirResourceProvider;
import com.example.hms.model.Notification;
import com.example.hms.payload.dto.PermissionFilterDTO;
import com.example.hms.service.ReceptionServiceImpl;
import com.example.hms.service.impl.NurseTaskServiceImpl;
import com.example.hms.service.impl.SuperAdminOrganizationOverviewServiceImpl;
import com.example.hms.specification.PermissionSpecification;
import com.example.hms.specification.UserRoleHospitalAssignmentSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * QA D1295 (round 28) on a real PostgreSQL built by Liquibase: every query
 * this round gave an order (the nurse inbox, and the D1295 class sweep of
 * {@code UnsortedPageGuardTest}) runs with the exact page or sort its caller
 * passes, for every parameter combination its callers use (nulls included).
 * The inbox is seeded and its order asserted; the others are run to prove the
 * ORDER BY the sort appends is valid SQL on their query (a {@code SELECT
 * DISTINCT} included).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("D1295: the ordered pages run on PostgreSQL")
class SortedPagePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_sorted_page")
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

    @Autowired private NotificationRepository notifications;
    @Autowired private PrescriptionRepository prescriptions;
    @Autowired private LabResultRepository labResults;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private EncounterRepository encounters;
    @Autowired private StaffRepository staff;
    @Autowired private PatientRepository patients;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private PermissionRepository permissions;

    private final List<UUID> savedNotifications = new ArrayList<>();

    @AfterEach
    void clear() {
        notifications.deleteAllById(savedNotifications);
    }

    @Test
    @DisplayName("the nurse inbox: every unread row newest first, then the read history newest first, capped")
    void nurseInboxIsOrdered() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 15, 28);
        // Written oldest last, so insertion order cannot pass for the sort.
        for (int i = 0; i < 25; i++) {
            save("nurse.d1295", "unread " + i, false, now.minusHours(48L - i));
        }
        save("nurse.d1295", "NEWS2 16", false, now);
        for (int i = 0; i < 30; i++) {
            save("nurse.d1295", "read " + i, true, now.minusHours(60L - i));
        }
        save("someone.else", "not mine", false, now.plusMinutes(1));

        List<Notification> unread = notifications.findByRecipientUsernameAndReadOrderByCreatedAtDesc("nurse.d1295", false);
        assertThat(unread).hasSize(26);
        assertThat(unread.get(0).getMessage()).isEqualTo("NEWS2 16");
        assertThat(unread).isSortedAccordingTo((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        List<Notification> history = notifications.findByRecipientUsernameAndRead("nurse.d1295", true,
            PageRequest.of(0, 20, NurseTaskServiceImpl.INBOX_NEWEST_FIRST)).getContent();
        assertThat(history).hasSize(20).allMatch(Notification::isRead);
        assertThat(history.get(0).getMessage()).isEqualTo("read 29");
        assertThat(history).isSortedAccordingTo((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
    }

    @Test
    @DisplayName("every other ordered page of the D1295 sweep is valid SQL on its query")
    void everySweptQueryRunsWithItsSort() {
        UUID any = UUID.randomUUID();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        assertThatCode(() -> {
            prescriptions.findByEncounter_Id(any, PrescriptionRepository.EVERY_ORDER_OF_THE_VISIT);
            prescriptions.findByPatient_Id(any, PageRequest.of(0, 200, MedicationRequestFhirResourceProvider.NEWEST_FIRST));
            labResults.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(any, any,
                PageRequest.of(0, 250, ObservationFhirResourceProvider.LAB_NEWEST_FIRST));
            invoices.findByPatient_IdAndHospital_Id(any, any, ReceptionServiceImpl.EVERY_INVOICE_NEWEST_FIRST);
            encounters.findVisitsByHospitalAndDateBetween(any, from, to, PageRequest.of(0, 500, Sort.by("id")));
            Sort oldest = SuperAdminOrganizationOverviewServiceImpl.OLDEST_FIRST;
            staff.findByHospitalIdExcludingDeletedUsers(any, PageRequest.of(0, 5, oldest));
            staff.findByHospitalIdAndActiveTrueExcludingDeletedUsers(any, PageRequest.of(0, 5, oldest));
            for (Boolean active : new Boolean[]{null, Boolean.TRUE, Boolean.FALSE}) {
                patients.findPageByHospitalIdAndActive(any, active, PageRequest.of(0, 5, oldest));
            }
            PageRequest newestAssignment = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "assignedAt", "id"));
            assignments.findAll(newestAssignment);
            assignments.findAll(UserRoleHospitalAssignmentSpecification.belongsToHospital(any)
                .and(UserRoleHospitalAssignmentSpecification.hasActive(true))
                .and(UserRoleHospitalAssignmentSpecification.matchesSearch("awa"))
                .and(UserRoleHospitalAssignmentSpecification.hasAssignmentCode("A-1")), newestAssignment);
            assignments.findAll(UserRoleHospitalAssignmentSpecification.hasActive(false), newestAssignment);
            PageRequest byName = PageRequest.of(0, 20, Sort.by("name", "id"));
            permissions.findAll(PermissionSpecification.build(new PermissionFilterDTO(null, null)), byName);
            permissions.findAll(PermissionSpecification.build(new PermissionFilterDTO(any, "read")), byName);
        }).doesNotThrowAnyException();
    }

    private void save(String recipient, String message, boolean read, LocalDateTime at) {
        Notification row = notifications.save(Notification.builder()
            .recipientUsername(recipient).message(message).read(read).createdAt(at).build());
        savedNotifications.add(row.getId());
    }
}
