package com.example.hms.service.disclosure;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.repository.AuditEventLogRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureAccountingServiceImpl} — "who has seen my record"
 * (Tier 2 item 39).
 *
 * <p>The behaviour worth pinning is what the predecessor got wrong. The old
 * implementation asked the ledger for
 * {@code (entityType='PATIENT', resourceId=patientId)}, a convention that
 * break-the-glass and eligibility rows do not follow, so the page a patient
 * opened to see who read their chart omitted every emergency override and
 * every disclosure to an insurer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureAccountingServiceImpl")
class DisclosureAccountingServiceImplTest {

    @Mock private AuditEventLogRepository auditRepository;

    @InjectMocks private DisclosureAccountingServiceImpl service;

    private UUID patientId;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        pageable = PageRequest.of(0, 20);
    }

    private AuditEventLog row(AuditEventType type, String entityType, String actor) {
        AuditEventLog log = AuditEventLog.builder()
            .eventType(type)
            .entityType(entityType)
            .userName(actor)
            .roleName("Doctor")
            .hospitalName("City Clinic")
            .eventDescription("desc")
            .resourceId(UUID.randomUUID().toString())
            .status(AuditStatus.SUCCESS)
            .eventTimestamp(LocalDateTime.now())
            .patientId(patientId)
            .build();
        log.setId(UUID.randomUUID());
        return log;
    }

    @Test
    @DisplayName("queries the patient key, not the entityType/resourceId convention")
    void queriesByPatientKey() {
        // The bug in one assertion. Break-the-glass rows carry
        // entityType=BREAK_GLASS_SESSION and resourceId=<session id>, so a
        // query keyed on the old convention cannot return them however it is
        // paged. Only patient_id reaches them.
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        service.getEntries(patientId, null, null, pageable);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AuditEventType>> types =
            ArgumentCaptor.forClass(Collection.class);
        verify(auditRepository)
            .findDisclosuresForPatient(eq(patientId), types.capture(), eq(null), eq(null), eq(pageable));

        assertThat(types.getValue())
            .as("break-the-glass must be fetched or the page cannot show emergency access")
            .contains(AuditEventType.BREAK_GLASS_ACCESS)
            .containsExactlyInAnyOrderElementsOf(DisclosureCategory.accountableEventTypes());
    }

    @Test
    @DisplayName("classifies each row and flags the ones that left the treating team")
    void classifiesRows() {
        Page<AuditEventLog> page = new PageImpl<>(List.of(
            row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "dr.alice"),
            row(AuditEventType.PATIENT_ACCESS, "EligibilityCheck", "reception.bob"),
            row(AuditEventType.PATIENT_ACCESS, "PATIENT", "dr.alice"),
            row(AuditEventType.RECORD_SHARE, "PATIENT", "him.clerk")
        ));
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(page);

        List<AccessLogEntryDTO> entries = service.getEntries(patientId, null, null, pageable).getContent();

        assertThat(entries).extracting(AccessLogEntryDTO::getCategory).containsExactly(
            DisclosureCategory.EMERGENCY_ACCESS,
            DisclosureCategory.INSURANCE,
            DisclosureCategory.TREATMENT_ACCESS,
            DisclosureCategory.SHARED_WITH_PROVIDER);

        assertThat(entries).extracting(AccessLogEntryDTO::isExternalDisclosure)
            .containsExactly(false, true, false, true);
    }

    @Test
    @DisplayName("carries the actor, their role and the hospital onto every entry")
    void entriesCarryTheActor() {
        // The portal's list binds these three. They were absent from the
        // mapping before Tier 2 item 39, which is why every row rendered
        // blank.
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(
                row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "dr.alice"))));

        AccessLogEntryDTO entry =
            service.getEntries(patientId, null, null, pageable).getContent().get(0);

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getActor()).isEqualTo("dr.alice");
        assertThat(entry.getActorRole()).isEqualTo("Doctor");
        assertThat(entry.getHospitalName()).isEqualTo("City Clinic");
        assertThat(entry.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("counts fold two group rows into one category without losing either")
    void countsFoldPatientAccessCorrectly() {
        // PATIENT_ACCESS groups separately per entity type, and two of those
        // groups can land in the same category. Assigning rather than summing
        // would drop one of them.
        when(auditRepository.countDisclosureCategoriesForPatient(any(), any(), any(), any()))
            .thenReturn(List.of(
                new Object[]{AuditEventType.PATIENT_ACCESS, "PATIENT", 7L},
                new Object[]{AuditEventType.PATIENT_ACCESS, "Patient", 3L},
                new Object[]{AuditEventType.PATIENT_ACCESS, "EligibilityCheck", 2L},
                new Object[]{AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", 1L},
                new Object[]{AuditEventType.RECORD_SHARE, "PATIENT", 1L}
            ));
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        DisclosureAccountingDTO out = service.getAccounting(patientId, null, null, pageable);

        assertThat(out.getCountsByCategory())
            .containsEntry(DisclosureCategory.TREATMENT_ACCESS, 10L)
            .containsEntry(DisclosureCategory.INSURANCE, 2L)
            .containsEntry(DisclosureCategory.EMERGENCY_ACCESS, 1L)
            .containsEntry(DisclosureCategory.SHARED_WITH_PROVIDER, 1L);

        assertThat(out.getTotalEvents()).isEqualTo(14L);
        // Insurance + share. Emergency access is the treating team, not an
        // outside party, and must not inflate this number.
        assertThat(out.getExternalDisclosures()).isEqualTo(3L);
    }

    @Test
    @DisplayName("the date window reaches the repository unaltered")
    void windowIsPassedThrough() {
        LocalDateTime from = LocalDateTime.now().minusYears(1);
        LocalDateTime to = LocalDateTime.now();
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));
        when(auditRepository.countDisclosureCategoriesForPatient(any(), any(), any(), any()))
            .thenReturn(List.of());

        DisclosureAccountingDTO out = service.getAccounting(patientId, from, to, pageable);

        verify(auditRepository)
            .findDisclosuresForPatient(eq(patientId), any(), eq(from), eq(to), eq(pageable));
        // Echoed back so a printed report states the window it covers rather
        // than implying it covers everything.
        assertThat(out.getFrom()).isEqualTo(from);
        assertThat(out.getTo()).isEqualTo(to);
    }

    @Test
    @DisplayName("an empty history is zero, not null")
    void emptyHistoryIsZero() {
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));
        when(auditRepository.countDisclosureCategoriesForPatient(any(), any(), any(), any()))
            .thenReturn(List.of());

        DisclosureAccountingDTO out = service.getAccounting(patientId, null, null, pageable);

        assertThat(out.getCountsByCategory()).isEmpty();
        assertThat(out.getTotalEvents()).isZero();
        assertThat(out.getExternalDisclosures()).isZero();
        assertThat(out.getEntries()).isEmpty();
    }
}
