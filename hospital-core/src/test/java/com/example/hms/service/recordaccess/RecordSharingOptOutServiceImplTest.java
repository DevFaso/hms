package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientRecordSharingOptOut;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No transaction is bound in a unit test, so {@code TransactionCallbacks.afterCommit}
 * runs inline — the audit assertions below observe it directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RecordSharingOptOutServiceImpl")
class RecordSharingOptOutServiceImplTest {

    @Mock private PatientRecordSharingOptOutRepository optOutRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private MessageSource messageSource;

    @InjectMocks private RecordSharingOptOutServiceImpl service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID actorUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Patient patient = new Patient();
        patient.setId(patientId);
        // Default actor is staff: the tenant-scoped finder answers. Self and global view stub the unscoped one.
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("msg");
        when(optOutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("status is 'none' when no opt-out is in force")
    void statusNone() {
        when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
            .thenReturn(Optional.empty());

        RecordSharingOptOutDTO dto = service.status(patientId, actorUserId, Locale.ENGLISH);

        assertThat(dto.inForce()).isFalse();
        assertThat(dto.patientId()).isEqualTo(patientId);
    }

    @Test
    @DisplayName("opting out records the row and emits CONSENT_UPDATE naming only the patient id")
    void optOutRecordsAndAudits() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(false);

        RecordSharingOptOutDTO dto = service.optOut(patientId, "my choice", actorUserId, Locale.ENGLISH);

        assertThat(dto.inForce()).isTrue();
        assertThat(dto.reason()).isEqualTo("my choice");

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CONSENT_UPDATE);
        assertThat(audit.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(audit.getValue().getUserId()).isEqualTo(actorUserId);
        // The reason is the patient's own words and may be PHI-adjacent: it
        // belongs on the row, never in the audit description.
        assertThat(audit.getValue().getEventDescription()).doesNotContain("my choice");
    }

    @Test
    @DisplayName("D533: the receptionist's opt-out at hospital A names hospital A on its CONSENT_UPDATE row")
    void d533_optOutAuditCarriesTheActingHospital() {
        UUID hospitalA = UUID.randomUUID();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .principalUserId(actorUserId)
                .activeHospitalId(hospitalA)
                .permittedHospitalIds(java.util.Set.of(hospitalA))
                .build());
        try {
            when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(false);

            service.optOut(patientId, "QA", actorUserId, Locale.ENGLISH);

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(audit.capture());
            assertThat(audit.getValue().getHospitalId()).isEqualTo(hospitalA);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    @DisplayName("D625: the PATIENT's own opt-out and opt-in name the patient's hospital on both CONSENT_UPDATE rows")
    void d625_patientOwnOptOutAndRevokeNameTheHospital() {
        UUID hospitalA = UUID.randomUUID();
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(hospitalA);
        hospital.setName("Hopital A");
        Patient own = new Patient();
        own.setId(patientId);
        own.setHospitalId(hospitalA);
        com.example.hms.model.PatientHospitalRegistration registration =
            new com.example.hms.model.PatientHospitalRegistration();
        registration.setHospital(hospital);
        own.getHospitalRegistrations().add(registration);
        // The patient acts on their own record: patient-owned scope, no pinned hospital.
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .principalUserId(actorUserId)
                .patientOwned(true)
                .build());
        when(patientRepository.findByUserId(actorUserId)).thenReturn(Optional.of(own));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(own));
        try {
            when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(false);
            service.optOut(patientId, null, actorUserId, Locale.ENGLISH);
            when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
                .thenReturn(Optional.of(com.example.hms.model.PatientRecordSharingOptOut.builder()
                    .patient(own).optedOutAt(java.time.LocalDateTime.now()).build()));
            service.revoke(patientId, actorUserId, Locale.ENGLISH);

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService, org.mockito.Mockito.times(2)).logEvent(audit.capture());
            assertThat(audit.getAllValues()).allSatisfy(row -> {
                assertThat(row.getHospitalId()).isEqualTo(hospitalA);
                assertThat(row.getHospitalName()).isEqualTo("Hopital A");
            });
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    @DisplayName("a second opt-out is a 409, not a second row")
    void optOutTwiceConflicts() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(true);

        assertThatThrownBy(() -> service.optOut(patientId, null, actorUserId, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);

        verify(optOutRepository, never()).save(any());
        verify(messageSource).getMessage(eq("recordaccess.optout.already"), any(), eq(Locale.ENGLISH));
    }

    @Test
    @DisplayName("revoking stamps revokedAt and keeps the row for the disclosure report")
    void revokeStampsNotDeletes() {
        PatientRecordSharingOptOut row = PatientRecordSharingOptOut.builder()
            .optedOutAt(LocalDateTime.now().minusDays(2)).build();
        when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
            .thenReturn(Optional.of(row));

        RecordSharingOptOutDTO dto = service.revoke(patientId, actorUserId, Locale.ENGLISH);

        assertThat(dto.inForce()).isFalse();
        assertThat(row.getRevokedAt()).isNotNull();
        assertThat(row.getRevokedByUserId()).isEqualTo(actorUserId);
        verify(optOutRepository, never()).delete(any());
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    @DisplayName("revoking with nothing in force is a 409")
    void revokeNothing() {
        when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(patientId, actorUserId, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);
        verify(messageSource).getMessage(eq("recordaccess.optout.none"), any(), eq(Locale.ENGLISH));
    }

    @Test
    @DisplayName("an unknown patient is a 404 carrying the key, not resolved prose")
    void unknownPatient() {
        UUID ghost = UUID.randomUUID();
        when(patientRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.status(ghost, actorUserId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("staff outside the patient's hospitals get 404 and nothing is written - the tenant finder decides")
    void staffOutsideTenantCannotReach() {
        UUID elsewhere = UUID.randomUUID();
        when(patientRepository.findById(elsewhere)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.optOut(elsewhere, "x", actorUserId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.revoke(elsewhere, actorUserId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientRepository, never()).findByIdUnscoped(elsewhere);
        verify(optOutRepository, never()).save(any());
    }

    @Test
    @DisplayName("the patient reaches their OWN row without any hospital scope")
    void patientReachesOwnRowUnscoped() {
        Patient own = new Patient();
        own.setId(patientId);
        when(patientRepository.findByUserId(actorUserId)).thenReturn(Optional.of(own));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());   // a patient has no tenant
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(own));

        assertThat(service.status(patientId, actorUserId, Locale.ENGLISH)).isNotNull();
        verify(patientRepository).findByIdUnscoped(patientId);
    }

    @Test
    @DisplayName("a super-admin in global view crosses tenants")
    void superAdminGlobalViewCrossesTenants() {
        Patient target = new Patient();
        target.setId(patientId);
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        try {
            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
            when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(target));
            assertThat(service.status(patientId, actorUserId, Locale.ENGLISH)).isNotNull();
            verify(patientRepository).findByIdUnscoped(patientId);
        } finally {
            HospitalContextHolder.clear();
        }
    }
}
