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
import java.util.Map;
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
    @DisplayName("legacy role spellings reach both audit surfaces bare, or null")
    void legacyRoleSpellingsAreNormalised() {
        // audit_event_logs.role_name holds ROLE_X from one writer, X from the
        // other, and the "Unknown Role" sentence; rows written years ago keep
        // theirs. toEntry() is the one choke point for the patient's access log
        // and the disclosure accounting, so it normalises for every client.
        AuditEventLog prefixed = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "a");
        prefixed.setRoleName("ROLE_DOCTOR");
        AuditEventLog bare = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "b");
        bare.setRoleName("NURSE");
        AuditEventLog sentence = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "c");
        sentence.setRoleName("Unknown Role");
        AuditEventLog missing = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "d");
        missing.setRoleName(null);
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(prefixed, bare, sentence, missing)));

        assertThat(service.getEntries(patientId, null, null, pageable).getContent())
            .extracting(AccessLogEntryDTO::getActorRole)
            .containsExactly("DOCTOR", "NURSE", null, null);
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

    @Test
    @DisplayName("QA D979: the same person sharing the record again within 30 minutes shows once")
    void repeatsFoldIntoOneLine() {
        LocalDateTime now = LocalDateTime.now();
        List<AuditEventLog> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AuditEventLog share = row(AuditEventType.RECORD_SHARE, "PATIENT", "Joséphine Hien");
            share.setEventTimestamp(now.minusSeconds(10L * i));
            rows.add(share);
        }
        AuditEventLog later = row(AuditEventType.RECORD_SHARE, "PATIENT", "Joséphine Hien");
        later.setEventTimestamp(now.minusHours(2));
        rows.add(later);
        AuditEventLog emergency1 = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr X");
        AuditEventLog emergency2 = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr X");
        rows.add(emergency1);
        rows.add(emergency2);
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(rows));

        List<AccessLogEntryDTO> shown = service.getEntriesAsPatientSees(patientId, null, null, pageable).getContent();

        assertThat(shown).filteredOn(e -> e.getCategory() == DisclosureCategory.SHARED_WITH_PROVIDER).hasSize(2);
        // The staff accounting of disclosures keeps every row.
        assertThat(service.getEntries(patientId, null, null, pageable).getContent())
            .filteredOn(e -> e.getCategory() == DisclosureCategory.SHARED_WITH_PROVIDER).hasSize(6);
        // Every emergency access stays a line of its own.
        assertThat(shown).filteredOn(e -> e.getCategory() == DisclosureCategory.EMERGENCY_ACCESS).hasSize(2);
    }

    @Test
    @DisplayName("QA D979: a repeat across a page boundary shows once, and the pager counts the lines shown")
    void aRepeatAcrossAPageBoundaryShowsOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<AuditEventLog> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            AuditEventLog share = row(AuditEventType.RECORD_SHARE, "PATIENT", "Joséphine Hien");
            share.setEventTimestamp(now.minusSeconds(10L * i));
            rows.add(share);
        }
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(rows));

        org.springframework.data.domain.Page<AccessLogEntryDTO> page =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA D979: rows beyond the fold window follow, read a window-sized page at a time")
    void olderRowsFollowInOrder() {
        List<AuditEventLog> window = new java.util.ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 2000; i++) {
            AuditEventLog r = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr " + i);
            r.setEventTimestamp(now.minusMinutes(i));
            window.add(r);
        }
        AuditEventLog oldest = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr old");
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable p = inv.getArgument(4);
            return new PageImpl<>(p.getPageNumber() == 0 ? window : List.of(oldest), p, 2001);
        });

        org.springframework.data.domain.Page<AccessLogEntryDTO> last =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(100, 20));

        assertThat(last.getTotalElements()).isEqualTo(2001);
        assertThat(last.getContent()).extracting(AccessLogEntryDTO::getActor).containsExactly("Dr old");
    }

    @Test
    @DisplayName("QA D979: unpaged, the patient's list folds every row, and its counts are the lines shown")
    void theUnpagedPatientListFoldsEveryRow() {
        LocalDateTime now = LocalDateTime.now();
        List<AuditEventLog> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            AuditEventLog share = row(AuditEventType.RECORD_SHARE, "PATIENT", "Dr Lu");
            share.setEventTimestamp(now.minusSeconds(5L * i));
            rows.add(share);
        }
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(rows));
        when(auditRepository.countDisclosureCategoriesForPatient(any(), any(), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[] {AuditEventType.RECORD_SHARE, "PATIENT", 3L}));

        assertThat(service.getEntriesAsPatientSees(patientId, null, null, Pageable.unpaged()).getContent()).hasSize(1);

        var accounting = service.getAccountingAsPatientSees(patientId, null, null, Pageable.unpaged());
        assertThat(accounting.getEntries()).hasSize(1);
        // Changed in round 17 (QA D979 reopened): the patient's header counted
        // every audit row (3) above one line, and could not be reconciled with
        // it. It now counts the lines the patient reads; the staff accounting
        // (getAccounting) still counts every row.
        assertThat(accounting.getTotalEvents()).isEqualTo(1);
        assertThat(accounting.getCountsByCategory()).containsEntry(DisclosureCategory.SHARED_WITH_PROVIDER, 1L);
    }

    /**
     * QA D979 (class guard): whatever the mix of readers, kinds and times, the
     * patient's header adds up to the lines of the patient's list, category by
     * category (within the fold window).
     */
    @Test
    @DisplayName("QA D979: the patient's header always adds up to its lines")
    void thePatientsHeaderAlwaysAddsUpToItsLines() {
        java.util.Random random = new java.util.Random(979);
        LocalDateTime now = LocalDateTime.now();
        AuditEventType[] types = {AuditEventType.RECORD_SHARE, AuditEventType.BREAK_GLASS_ACCESS};
        String[] entities = {"PATIENT", "BREAK_GLASS_SESSION"};
        for (int run = 0; run < 20; run++) {
            List<AuditEventLog> rows = new java.util.ArrayList<>();
            Map<String, Long> raw = new java.util.HashMap<>();
            int n = 1 + random.nextInt(40);
            for (int i = 0; i < n; i++) {
                int kind = random.nextInt(2);
                AuditEventLog r = row(types[kind], entities[kind], "Dr " + random.nextInt(3));
                r.setEventDescription("d" + random.nextInt(3));
                r.setEventTimestamp(now.minusMinutes(random.nextInt(180)));
                rows.add(r);
                raw.merge(types[kind] + "|" + entities[kind], 1L, Long::sum);
            }
            rows.sort(java.util.Comparator.comparing(AuditEventLog::getEventTimestamp).reversed());
            org.mockito.Mockito.reset(auditRepository);
            when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(rows));
            List<Object[]> counted = raw.entrySet().stream()
                .map(e -> new Object[] {AuditEventType.valueOf(e.getKey().split("\\|")[0]), e.getKey().split("\\|")[1],
                    e.getValue()})
                .toList();
            when(auditRepository.countDisclosureCategoriesForPatient(any(), any(), any(), any())).thenReturn(counted);

            var accounting = service.getAccountingAsPatientSees(patientId, null, null, PageRequest.of(0, 2000));

            Map<DisclosureCategory, Long> shown = new java.util.EnumMap<>(DisclosureCategory.class);
            accounting.getEntries().forEach(e -> shown.merge(e.getCategory(), 1L, Long::sum));
            assertThat(accounting.getCountsByCategory()).as("run %d", run).containsAllEntriesOf(shown);
            assertThat(accounting.getTotalEvents()).as("run %d", run).isEqualTo(accounting.getEntries().size());
        }
    }

    @Test
    @DisplayName("QA D979 (round 17): two reads by the same person that differ only in description show once")
    void aRepeatWithAnotherDescriptionStillFolds() {
        LocalDateTime now = LocalDateTime.now();
        AuditEventLog chart = row(AuditEventType.RECORD_SHARE, "PATIENT", "Joséphine Hien");
        chart.setEventTimestamp(now);
        chart.setEventDescription("Chart read");
        AuditEventLog timeline = row(AuditEventType.RECORD_SHARE, "PATIENT", "Joséphine Hien");
        timeline.setEventTimestamp(now.minusSeconds(20));
        timeline.setEventDescription("Timeline read");
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(chart, timeline)));
        when(auditRepository.countDisclosureCategoriesForPatient(any(), any(), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[] {AuditEventType.RECORD_SHARE, "PATIENT", 2L}));

        var accounting = service.getAccountingAsPatientSees(patientId, null, null, PageRequest.of(0, 20));

        assertThat(accounting.getEntries()).hasSize(1);
        assertThat(accounting.getCountsByCategory()).containsEntry(DisclosureCategory.SHARED_WITH_PROVIDER, 1L);
        assertThat(accounting.getTotalEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA D979: a page that straddles the folded lines and the older rows reads both")
    void aPageStraddlingTheWindowReadsBoth() {
        List<AuditEventLog> window = new java.util.ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 2000; i++) {
            AuditEventLog r = row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr " + i);
            r.setEventTimestamp(now.minusMinutes(i));
            window.add(r);
        }
        List<AuditEventLog> older = List.of(
            row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr old 1"),
            row(AuditEventType.BREAK_GLASS_ACCESS, "BREAK_GLASS_SESSION", "Dr old 2"));
        when(auditRepository.findDisclosuresForPatient(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            org.springframework.data.domain.Pageable p = inv.getArgument(4);
            return new PageImpl<>(p.getPageNumber() == 0 ? window : older, p, 2002);
        });

        org.springframework.data.domain.Page<AccessLogEntryDTO> page =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(1998, 1));
        org.springframework.data.domain.Page<AccessLogEntryDTO> straddle =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(199, 10));

        assertThat(page.getContent()).extracting(AccessLogEntryDTO::getActor).containsExactly("Dr 1998");
        assertThat(straddle.getContent()).extracting(AccessLogEntryDTO::getActor)
            .containsExactly("Dr 1990", "Dr 1991", "Dr 1992", "Dr 1993", "Dr 1994", "Dr 1995", "Dr 1996",
                "Dr 1997", "Dr 1998", "Dr 1999");
        org.springframework.data.domain.Page<AccessLogEntryDTO> tail =
            service.getEntriesAsPatientSees(patientId, null, null, PageRequest.of(1000, 2));
        assertThat(tail.getContent()).extracting(AccessLogEntryDTO::getActor).containsExactly("Dr old 1", "Dr old 2");
    }
}
