package com.example.hms.service;

import com.example.hms.enums.DeathDisputeStatus;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.DeathDispute;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.repository.DeathDisputeRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.impl.DeathDisputeServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The urgent dispute of a recorded death. NOT clinically approved: the designated clinical lead has to sign it off. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeathDisputeServiceImplTest {

    private final OrderReviewHoldService orderReview = org.mockito.Mockito.mock(OrderReviewHoldService.class);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 0);

    @Mock private DeathDisputeRepository disputes;
    @Mock private DeathRecordRepository records;
    @Mock private PatientRepository patients;
    @Mock private PatientChartAccess chartAccess;
    @Mock private RoleValidator roles;
    @Mock private UserRoleHospitalAssignmentRepository assignments;
    @Mock private InBasketService inBasket;
    @Mock private AuditEventLogService audit;

    private DeathDisputeServiceImpl service;
    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private Patient patient;
    private DeathRecord record;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        service = new DeathDisputeServiceImpl(Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC),
            disputes, records, patients, chartAccess, roles, assignments, inBasket, audit, orderReview);
        patient = new Patient();
        patient.setId(patientId);
        patient.setDeceasedAt(NOW.minusDays(2));
        Hospital recording = new Hospital();
        recording.setId(hospitalA);
        record = DeathRecord.builder().patient(patient).hospital(recording).diedAt(NOW.minusDays(2)).immediateCause("x")
            .version(3L).build();
        record.setId(UUID.randomUUID());
        when(roles.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roles.getCurrentUserId()).thenReturn(doctor);
        when(roles.isSigningPhysician(doctor, hospitalB)).thenReturn(true);
        when(chartAccess.require(patientId, hospitalB)).thenReturn(patient);
        when(records.lockLiveByPatientId(patientId)).thenReturn(Optional.of(record));
        when(records.findById(record.getId())).thenReturn(Optional.of(record));
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(disputes.findOpen(record.getId(), hospitalB)).thenReturn(Optional.empty());
        when(disputes.save(any(DeathDispute.class))).thenAnswer(call -> {
            DeathDispute row = call.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalB), any())).thenReturn(List.of(user()));
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalA), any())).thenReturn(List.of(user()));
    }

    private static User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static DeathDisputeRequestDTO statement() {
        return DeathDisputeRequestDTO.builder().reason(" Seen alive ").assessedAlive(true).identityVerified(true).build();
    }

    @Test
    @DisplayName("opens at this hospital, leaves the record as it was, audits it, and files URGENT review in this transaction")
    void opens() {
        var result = service.dispute(patientId, statement());

        ArgumentCaptor<DeathDispute> row = ArgumentCaptor.forClass(DeathDispute.class);
        verify(disputes).save(row.capture());
        assertThat(row.getValue().getHospitalId()).isEqualTo(hospitalB);
        assertThat(row.getValue().getDeathRecordVersion()).isEqualTo(3L);
        assertThat(row.getValue().getReason()).isEqualTo("Seen alive");
        assertThat(row.getValue().getReviewFiledAt()).isEqualTo(NOW);
        assertThat(result.getStatusMessage()).isEqualTo("Death status disputed, care permitted pending review");
        verify(records, never()).save(any());
        assertThat(patient.isDeceased()).as("the death entry is kept").isTrue();

        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(2)).createItem(item.capture());
        verify(inBasket, never()).createItemInNewTransaction(any());
        assertThat(item.getAllValues()).allSatisfy(i -> assertThat(i.getPriority()).isEqualTo(InBasketPriority.URGENT));
        assertThat(item.getAllValues()).extracting(CreateInBasketItemRequest::getHospitalId)
            .containsExactlyInAnyOrder(hospitalA, hospitalB);

        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventDescription()).contains(doctor.toString()).contains(hospitalB.toString())
            .contains(record.getId().toString()).contains("version 3").contains("Seen alive");
    }

    @Test
    @DisplayName("another hospital's reader sees nothing of the record")
    void nothingOfAnotherHospitalsRecord() {
        assertThat(service.dispute(patientId, statement()).getDeathRecordId()).isNull();
    }

    @Test
    @DisplayName("only a doctor at THIS hospital: the union of assignments, an admin, and a doctor elsewhere are refused")
    void onlyADoctorHere() {
        when(roles.isSigningPhysician(doctor, hospitalB)).thenReturn(false);
        when(roles.isHospitalAdmin(doctor, hospitalB)).thenReturn(true);
        when(roles.isSigningPhysician(doctor, hospitalA)).thenReturn(true);
        DeathDisputeRequestDTO body = statement();

        assertThatThrownBy(() -> service.dispute(patientId, body)).isInstanceOf(AccessDeniedException.class);
        verify(disputes, never()).save(any());
    }

    @Test
    @DisplayName("a super admin in global view, with no hospital, is refused")
    void noHospital() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        DeathDisputeRequestDTO body = statement();

        assertThatThrownBy(() -> service.dispute(patientId, body)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("each statement and the reason are refused on their own field")
    void statementsOnTheirField() {
        DeathDisputeRequestDTO none = DeathDisputeRequestDTO.builder().reason(" ").assessedAlive(false).identityVerified(null).build();

        assertThatThrownBy(() -> service.dispute(patientId, none))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getFieldErrors().keySet())
                .containsExactlyInAnyOrder("reason", "assessedAlive", "identityVerified"));
        verify(disputes, never()).save(any());
    }

    @Test
    @DisplayName("a patient registered only elsewhere answers as unknown, before anything is read of the death")
    void tenancy() {
        when(chartAccess.require(patientId, hospitalB)).thenThrow(new ResourceNotFoundException("patient.notFound", patientId));
        DeathDisputeRequestDTO body = statement();

        assertThatThrownBy(() -> service.dispute(patientId, body)).isInstanceOf(ResourceNotFoundException.class);
        verify(records, never()).lockLiveByPatientId(any());
    }

    @Test
    @DisplayName("no death on record (entered in error meanwhile) is refused")
    void noDeath() {
        when(records.lockLiveByPatientId(patientId)).thenReturn(Optional.empty());
        DeathDisputeRequestDTO body = statement();

        assertThatThrownBy(() -> service.dispute(patientId, body))
            .isInstanceOf(BusinessException.class).isNotInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("a repeat answers with the open dispute and files nothing")
    void repeat() {
        DeathDispute open = openDispute();
        when(disputes.findOpen(record.getId(), hospitalB)).thenReturn(Optional.of(open));

        assertThat(service.dispute(patientId, statement()).getId()).isEqualTo(open.getId());
        verify(disputes, never()).save(any());
        verify(inBasket, never()).createItem(any());
    }

    private DeathDispute openDispute() {
        DeathDispute open = DeathDispute.builder().deathRecordId(record.getId()).deathRecordVersion(3L).patientId(patientId)
            .hospitalId(hospitalB).disputedById(doctor).disputedAt(NOW.minusHours(10)).reason("Seen alive")
            .assessedAlive(true).identityVerified(true).reviewFiledAt(NOW.minusHours(10)).build();
        open.setId(UUID.randomUUID());
        when(disputes.lockById(open.getId())).thenReturn(Optional.of(open));
        return open;
    }

    private void actingAdminAt(UUID hospital) {
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
        when(roles.isHospitalAdmin(doctor, hospital)).thenReturn(true);
    }

    @Test
    @DisplayName("death upheld: closed with who, when and why, the record untouched, the review items removed, audited")
    void upheld() {
        DeathDispute open = openDispute();
        actingAdminAt(hospitalB);

        var result = service.uphold(open.getId(), DeathDisputeOutcomeDTO.builder().reason("Family confirmed").build());

        assertThat(open.getStatus()).isEqualTo(DeathDisputeStatus.UPHELD);
        assertThat(open.getResolutionReason()).isEqualTo("Family confirmed");
        assertThat(open.getResolvedAt()).isEqualTo(NOW);
        assertThat(result.getStatusMessage()).as("no longer disputed").isNull();
        org.mockito.Mockito.verify(orderReview).disputeClosed(open.getId());
        verify(inBasket, atLeastOnce()).removeOpenItemsForReference(any(), anyString());
        verify(audit).logEvent(any());
        verify(records, never()).save(any());
    }

    @Test
    @DisplayName("the recording hospital's administrator may decide too; a third hospital's answers as unknown")
    void whoDecides() {
        DeathDispute open = openDispute();
        UUID third = UUID.randomUUID();
        actingAdminAt(third);
        UUID id = open.getId();
        DeathDisputeOutcomeDTO outcome = DeathDisputeOutcomeDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.uphold(id, outcome)).isInstanceOf(ResourceNotFoundException.class);

        actingAdminAt(hospitalA);
        service.uphold(id, outcome);
        assertThat(open.getStatus()).isEqualTo(DeathDisputeStatus.UPHELD);
    }

    @Test
    @DisplayName("a doctor is not an administrator: upholding asks the role at the acting hospital")
    void aDoctorDoesNotDecide() {
        DeathDispute open = openDispute();
        UUID id = open.getId();
        DeathDisputeOutcomeDTO outcome = DeathDisputeOutcomeDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.uphold(id, outcome)).isInstanceOf(AccessDeniedException.class);
        assertThat(open.isOpen()).isTrue();
    }

    @Test
    @DisplayName("upholding needs a reason, and a dispute decided is not decided again")
    void upholdRefusals() {
        DeathDispute open = openDispute();
        actingAdminAt(hospitalB);
        UUID id = open.getId();
        DeathDisputeOutcomeDTO blank = DeathDisputeOutcomeDTO.builder().reason(" ").build();
        DeathDisputeOutcomeDTO outcome = DeathDisputeOutcomeDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.uphold(id, blank))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("reason"));
        service.uphold(id, outcome);
        assertThatThrownBy(() -> service.uphold(id, outcome)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("nobody to tell: the dispute still stands, and the sweep files it when somebody is there")
    void nobodyToTell() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of());

        service.dispute(patientId, statement());

        ArgumentCaptor<DeathDispute> row = ArgumentCaptor.forClass(DeathDispute.class);
        verify(disputes).save(row.capture());
        assertThat(row.getValue().getReviewFiledAt()).isNull();
        assertThat(row.getValue().isOpen()).isTrue();

        when(disputes.findDueForEscalation(any())).thenReturn(List.of(row.getValue().getId()));
        when(disputes.lockById(row.getValue().getId())).thenReturn(Optional.of(row.getValue()));
        assertThat(service.escalateUnreviewed()).isZero();
        assertThat(row.getValue().getReviewFiledAt()).isNull();

        when(assignments.findLiveUsersAtHospitalWithRoleCodes(eq(hospitalB), any())).thenReturn(List.of(user()));
        assertThat(service.escalateUnreviewed()).isEqualTo(1);
        assertThat(row.getValue().getReviewFiledAt()).isEqualTo(NOW);
        assertThat(row.getValue().getEscalationCount()).as("a first filing is no escalation").isZero();
    }

    @Test
    @DisplayName("unreviewed: escalated under its own reference, and the dispute stays open, however long")
    void escalates() {
        DeathDispute open = openDispute();
        when(disputes.findDueForEscalation(NOW.minusHours(4))).thenReturn(List.of(open.getId()));

        assertThat(service.escalateUnreviewed()).isEqualTo(2);
        assertThat(service.escalateUnreviewed()).isEqualTo(2);

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket, times(4)).createItem(items.capture());
        assertThat(Set.copyOf(items.getAllValues().stream().map(CreateInBasketItemRequest::getReferenceId).toList())).hasSize(2);
        assertThat(open.getEscalationCount()).isEqualTo(2);
        assertThat(open.getLastEscalatedAt()).isEqualTo(NOW);
        assertThat(open.isOpen()).as("an unresolved dispute never expires").isTrue();
    }

    @Test
    @DisplayName("the sweep reads the row under its lock: a dispute decided since the list was read stays decided")
    void theSweepNeverReopens() {
        DeathDispute open = openDispute();
        open.setStatus(DeathDisputeStatus.UPHELD);
        when(disputes.findDueForEscalation(any())).thenReturn(List.of(open.getId()));

        assertThat(service.escalateUnreviewed()).isZero();
        assertThat(open.getStatus()).isEqualTo(DeathDisputeStatus.UPHELD);
        assertThat(open.getEscalationCount()).isZero();
        verify(inBasket, never()).createItem(any());
    }

    @Test
    @DisplayName("the review list asks the role at the acting hospital, not the union of assignments")
    void theReviewListAsksTheRoleHere() {
        assertThatThrownBy(() -> service.openForReview()).isInstanceOf(AccessDeniedException.class);

        actingAdminAt(hospitalB);
        when(disputes.findOpenForReview(hospitalB)).thenReturn(List.of());
        assertThat(service.openForReview()).isEmpty();
    }

    @Test
    @DisplayName("an outcome is audited at the hospital where it was decided")
    void theOutcomeAuditIsAnchoredWhereDecided() {
        DeathDispute open = openDispute();
        actingAdminAt(hospitalA);

        service.uphold(open.getId(), DeathDisputeOutcomeDTO.builder().reason("x").build());

        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getHospitalId()).isEqualTo(hospitalA);
    }
}
