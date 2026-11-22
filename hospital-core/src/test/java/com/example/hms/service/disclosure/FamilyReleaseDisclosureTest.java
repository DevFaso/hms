package com.example.hms.service.disclosure;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.ProxySensitiveRelease;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.ProxySensitiveReleaseRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3451 (r70): the minor is told that her HIV result went to her parent: who released it, to whom, when, and
 * which category of result. Never the value, never the clinician's reason, never the test.
 */
@DisplayName("D3451: a result released to the family is on the patient's disclosure list")
class FamilyReleaseDisclosureTest {

    private final AuditEventLogRepository auditRepository = mock(AuditEventLogRepository.class);
    private final ProxySensitiveReleaseRepository releases = mock(ProxySensitiveReleaseRepository.class);
    private final PatientProxyRepository grants = mock(PatientProxyRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DisclosureAccountingServiceImpl service = new DisclosureAccountingServiceImpl(auditRepository);

    private final UUID patientId = UUID.randomUUID();
    private final UUID motherUserId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();
    private ProxySensitiveRelease release;
    private final List<AuditEventLog> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service.setFamilyReleaseDisclosure(new FamilyReleaseDisclosure(releases, grants, users, new ObjectMapper()));
        release = ProxySensitiveRelease.builder()
            .hospitalId(UUID.randomUUID()).patientId(patientId).proxyGrantId(grantId).proxyUserId(motherUserId)
            .labOrderId(UUID.randomUUID()).sensitivityCategory(SensitivityCategory.HIV)
            .reason("Séropositivité à annoncer avec la mère, en vue du traitement")
            .releasedByUserId(UUID.randomUUID()).releasedAt(LocalDateTime.of(2026, 11, 22, 14, 5)).build();
        release.setId(UUID.randomUUID());
        when(releases.findById(release.getId())).thenReturn(Optional.of(release));
        User mother = new User();
        mother.setId(motherUserId);
        mother.setFirstName("Patient");
        mother.setLastName("Test 11");
        mother.setUsername("a11.portal");
        when(users.findById(motherUserId)).thenReturn(Optional.of(mother));
        PatientProxy grant = new PatientProxy();
        grant.setRelationship(ProxyRelationship.PARENT);
        when(grants.findById(grantId)).thenReturn(Optional.of(grant));
        when(auditRepository.findDisclosuresForPatient(eq(patientId), any(), any(), any(), any()))
            .thenAnswer(call -> new PageImpl<>(rows));
        when(auditRepository.countDisclosureCategoriesForPatient(eq(patientId), any(), any(), any()))
            .thenAnswer(call -> {
                List<Object[]> counted = new ArrayList<>();
                rows.forEach(row -> counted.add(new Object[] {row.getEventType(), row.getEntityType(), 1L}));
                return counted;
            });
    }

    private AuditEventLog releaseRow(UUID ofPatient, String details) {
        AuditEventLog row = new AuditEventLog();
        row.setId(UUID.randomUUID());
        row.setPatientId(ofPatient);
        row.setEventType(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY);
        row.setEntityType("PROXY_SENSITIVE_RELEASE");
        row.setResourceId(release.getLabOrderId().toString());
        row.setUserName("Test Médecin T1");
        row.setRoleName("ROLE_DOCTOR");
        row.setHospitalName("Clinique Test A");
        row.setEventDescription("Sensitive result (HIV) released to a verified parent or guardian");
        row.setEventTimestamp(LocalDateTime.of(2026, 11, 22, 14, 5));
        row.setDetails(details);
        rows.add(row);
        return row;
    }

    private String detailsNaming(UUID releaseId) {
        return "{\"releaseId\":\"" + releaseId + "\",\"labOrderId\":\"" + release.getLabOrderId()
            + "\",\"grantId\":\"" + grantId + "\",\"relationship\":\"PARENT\",\"category\":\"HIV\"}";
    }

    @Test
    @DisplayName("the release is an accountable event, classified as a disclosure outside the treating team")
    void theReleaseIsAccountable() {
        assertThat(DisclosureCategory.accountableEventTypes())
            .contains(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY);
        assertThat(DisclosureCategory.classify(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY,
            "PROXY_SENSITIVE_RELEASE")).isEqualTo(DisclosureCategory.RELEASED_TO_FAMILY);
        assertThat(DisclosureCategory.RELEASED_TO_FAMILY.isExternalDisclosure()).isTrue();
        // Verifying a parent's link discloses nothing of the record: it stays off the list.
        assertThat(DisclosureCategory.classify(AuditEventType.PROXY_RELATIONSHIP_VERIFIED, "PATIENT_PROXY")).isNull();
    }

    @Test
    @DisplayName("the r70 case: who released it, to whom (the mother, a parent), when, and the HIV category; no value, no reason")
    void theMinorReadsWhoToWhomWhenAndWhichCategory() {
        releaseRow(patientId, detailsNaming(release.getId()));

        DisclosureAccountingDTO accounting =
            service.getAccountingAsPatientSees(patientId, null, null, PageRequest.of(0, 20));

        assertThat(accounting.getEntries()).hasSize(1);
        AccessLogEntryDTO entry = accounting.getEntries().get(0);
        assertThat(entry.getCategory()).isEqualTo(DisclosureCategory.RELEASED_TO_FAMILY);
        assertThat(entry.isExternalDisclosure()).isTrue();
        assertThat(entry.getActor()).isEqualTo("Test Médecin T1");
        assertThat(entry.getRecipientName()).isEqualTo("Patient Test 11");
        assertThat(entry.getRecipientRelationship()).isEqualTo("PARENT");
        assertThat(entry.getResultCategory()).isEqualTo("HIV");
        assertThat(entry.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 11, 22, 14, 5));
        assertThat(accounting.getCountsByCategory()).containsEntry(DisclosureCategory.RELEASED_TO_FAMILY, 1L);
        assertThat(accounting.getExternalDisclosures()).isEqualTo(1);
        // Nothing of the result itself, and not the clinician's reason (it can say what the result is).
        assertThat(entry.toString()).doesNotContain("Séropositivité").doesNotContain("Positif")
            .doesNotContain("a11.portal");
    }

    @Test
    @DisplayName("two releases of one result (to each parent) are two lines: a release is never folded")
    void releasesAreNeverFolded() {
        releaseRow(patientId, detailsNaming(release.getId()));
        releaseRow(patientId, detailsNaming(release.getId()));

        assertThat(service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(0, 20)).getContent())
            .hasSize(2);
    }

    @Test
    @DisplayName("a row that names another patient's release, or none that can be read, is listed without a name")
    void anUnreadableReleaseAddsNothing() {
        ProxySensitiveRelease another = ProxySensitiveRelease.builder().patientId(UUID.randomUUID())
            .proxyGrantId(grantId).proxyUserId(motherUserId).sensitivityCategory(SensitivityCategory.HIV).build();
        another.setId(UUID.randomUUID());
        when(releases.findById(another.getId())).thenReturn(Optional.of(another));
        releaseRow(patientId, detailsNaming(another.getId()));
        releaseRow(patientId, "not json");
        releaseRow(patientId, null);
        releaseRow(patientId, "{\"releaseId\":\"not-a-uuid\"}");

        List<AccessLogEntryDTO> entries =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(0, 20)).getContent();

        assertThat(entries).hasSize(4).allSatisfy(entry -> {
            assertThat(entry.getCategory()).isEqualTo(DisclosureCategory.RELEASED_TO_FAMILY);
            assertThat(entry.getRecipientName()).isNull();
            assertThat(entry.getRecipientRelationship()).isNull();
            assertThat(entry.getResultCategory()).isNull();
        });
        verify(users, never()).findById(any());
    }

    @Test
    @DisplayName("an ordinary chart read is not enriched and asks nothing of the releases")
    void otherRowsAreUntouched() {
        AuditEventLog read = new AuditEventLog();
        read.setId(UUID.randomUUID());
        read.setPatientId(patientId);
        read.setEventType(AuditEventType.PATIENT_ACCESS);
        read.setEntityType("PATIENT");
        read.setUserName("Test Médecin T1");
        read.setEventTimestamp(LocalDateTime.of(2026, 11, 22, 9, 0));
        rows.add(read);

        AccessLogEntryDTO entry =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(0, 20)).getContent().get(0);

        assertThat(entry.getCategory()).isEqualTo(DisclosureCategory.TREATMENT_ACCESS);
        assertThat(entry.getRecipientName()).isNull();
        verify(releases, never()).findById(any());
    }

    // ── Review of #1290: the STAFF accounting ───────────────────────────────

    @Test
    @DisplayName("review of #1290: with no hospital to place the reader at, the staff accounting asks for no family release at all")
    void theGlobalStaffAccountingListsNoRelease() {
        releaseRow(patientId, detailsNaming(release.getId()));
        org.mockito.ArgumentCaptor<java.util.Collection<AuditEventType>> asked =
            org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);

        service.getAccounting(patientId, null, null, PageRequest.of(0, 20));
        service.getEntries(patientId, null, null, PageRequest.of(0, 20));

        verify(auditRepository, org.mockito.Mockito.times(2))
            .findDisclosuresForPatient(eq(patientId), asked.capture(), any(), any(), any());
        assertThat(asked.getAllValues()).allSatisfy(types -> assertThat(types)
            .doesNotContain(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY)
            .contains(AuditEventType.BREAK_GLASS_ACCESS, AuditEventType.PATIENT_ACCESS));
        org.mockito.ArgumentCaptor<java.util.Collection<AuditEventType>> counted =
            org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(auditRepository).countDisclosureCategoriesForPatient(eq(patientId), counted.capture(), any(), any());
        assertThat(counted.getValue()).doesNotContain(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY);
        verify(auditRepository, never()).findDisclosuresForPatientAtHospital(any(), any(), any(), any(), any(), any(),
            any());
    }

    @Test
    @DisplayName("review of #1290: another hospital's staff never see the release; the releasing hospital's staff see the row, without the recipient or the category")
    void anotherHospitalsStaffNeverSeeTheRelease() {
        UUID releasingHospital = release.getHospitalId();
        UUID otherHospital = UUID.randomUUID();
        AuditEventLog row = releaseRow(patientId, detailsNaming(release.getId()));
        row.setHospitalId(releasingHospital);
        // The repository's own rule, as its query states it: a release row only at the hospital that wrote it.
        when(auditRepository.findDisclosuresForPatientAtHospital(eq(patientId), any(),
                eq(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY), any(), any(), any(), any()))
            .thenAnswer(call -> new PageImpl<>(rows.stream()
                .filter(r -> r.getEventType() != AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY
                    || java.util.Objects.equals(r.getHospitalId(), call.getArgument(3)))
                .toList()));
        when(auditRepository.countDisclosureCategoriesForPatientAtHospital(eq(patientId), any(),
                eq(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY), any(), any(), any()))
            .thenAnswer(call -> {
                List<Object[]> counted = new ArrayList<>();
                rows.stream().filter(r -> java.util.Objects.equals(r.getHospitalId(), call.getArgument(3)))
                    .forEach(r -> counted.add(new Object[] {r.getEventType(), r.getEntityType(), 1L}));
                return counted;
            });

        DisclosureAccountingDTO elsewhere =
            service.getAccounting(patientId, null, null, PageRequest.of(0, 20), otherHospital);
        assertThat(elsewhere.getEntries()).isEmpty();
        assertThat(elsewhere.getCountsByCategory()).doesNotContainKey(DisclosureCategory.RELEASED_TO_FAMILY);
        assertThat(elsewhere.getTotalEvents()).isZero();

        DisclosureAccountingDTO here =
            service.getAccounting(patientId, null, null, PageRequest.of(0, 20), releasingHospital);
        assertThat(here.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getCategory()).isEqualTo(DisclosureCategory.RELEASED_TO_FAMILY);
            assertThat(entry.getActor()).isEqualTo("Test Médecin T1");
            // Staff rows never carry whom it went to, which category it was, or the audit sentence that says it.
            assertThat(entry.getRecipientName()).isNull();
            assertThat(entry.getRecipientRelationship()).isNull();
            assertThat(entry.getResultCategory()).isNull();
            assertThat(entry.getDescription()).isNull();
            assertThat(entry.toString()).doesNotContain("HIV").doesNotContain("Patient Test 11");
        });
        assertThat(here.getCountsByCategory()).containsEntry(DisclosureCategory.RELEASED_TO_FAMILY, 1L);
        verify(releases, never()).findById(any());
        // The scoped query is asked with the caller's hospital, never unscoped.
        verify(auditRepository, never()).findDisclosuresForPatient(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the patient's own list keeps every release, from every hospital, with the recipient and the category")
    void thePatientStillReadsEveryRelease() {
        AuditEventLog row = releaseRow(patientId, detailsNaming(release.getId()));
        row.setHospitalId(UUID.randomUUID());

        AccessLogEntryDTO entry =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(0, 20)).getContent().get(0);

        assertThat(entry.getRecipientName()).isEqualTo("Patient Test 11");
        assertThat(entry.getResultCategory()).isEqualTo("HIV");
        assertThat(entry.getDescription()).as("the audit sentence is staff wording").isNull();
    }
}
