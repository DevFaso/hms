package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.HospitalLifecycleResponseDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HospitalLifecycleServiceImplTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private MfaService mfaService;

    @InjectMocks private HospitalLifecycleServiceImpl service;

    private UUID hospitalId;
    private UUID actorId;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Acme General");
        hospital.setCode("ACME-GEN");
        hospital.setLifecycleState(HospitalLifecycleState.ACTIVE);

        // Default to non-strict, MFA off so tests don't need to mock MFA service.
        ReflectionTestUtils.setField(service, "requireMfa", false);
        ReflectionTestUtils.setField(service, "requireMfaStrict", false);

        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(actorId)
            .principalUsername("super.admin")
            .superAdmin(true)
            .permittedHospitalIds(Set.of(hospitalId))
            .build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    private TenantLifecycleActionRequestDTO withReason(String reason) {
        return TenantLifecycleActionRequestDTO.builder().reason(reason).build();
    }

    @Test
    void suspendTransitionsActiveHospitalAndEmitsAudit() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

        HospitalLifecycleResponseDTO result = service.suspend(hospitalId, withReason("ops review"), null);

        assertThat(result.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        assertThat(result.getSuspendedBy()).isEqualTo(actorId);
        assertThat(result.getSuspensionReason()).isEqualTo("ops review");
        assertThat(result.isCanRestore()).isTrue();
        assertThat(result.isCanSuspend()).isFalse();
        // Mirror onto legacy `active` flag.
        assertThat(hospital.isActive()).isFalse();

        ArgumentCaptor<AuditEventRequestDTO> cap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AuditEventType.HOSPITAL_SUSPENDED);
        assertThat(cap.getValue().getResourceId()).isEqualTo(hospitalId.toString());
    }

    @Test
    void suspendRequiresAReason() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        TenantLifecycleActionRequestDTO blank = withReason("  ");
        assertThatThrownBy(() -> service.suspend(hospitalId, blank, null))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("reason is required");
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    void suspendRejectsAlreadySuspendedHospital() {
        hospital.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        TenantLifecycleActionRequestDTO req = withReason("ops");
        assertThatThrownBy(() -> service.suspend(hospitalId, req, null))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Cannot suspend");
    }

    @Test
    void restoreTransitionsSuspendedToActive() {
        hospital.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        hospital.setActive(false);
        hospital.setSuspendedAt(Instant.now());
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

        HospitalLifecycleResponseDTO result = service.restore(hospitalId, null);

        assertThat(result.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
        assertThat(hospital.isActive()).isTrue();
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void archiveTransitionsActiveAndCanLaterSchedulePurge() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

        service.archive(hospitalId, withReason("offboarded"), null);
        assertThat(hospital.getLifecycleState()).isEqualTo(HospitalLifecycleState.ARCHIVED);

        HospitalLifecycleResponseDTO result = service.schedulePurge(
            hospitalId, withReason("retention expired"), null);
        assertThat(result.getLifecycleState()).isEqualTo(HospitalLifecycleState.PENDING_PURGE);
        assertThat(result.getPurgeScheduledFor()).isAfter(Instant.now());
        assertThat(result.getPurgeReason()).isEqualTo("retention expired");
    }

    @Test
    void schedulePurgeOnlyValidFromArchived() {
        // ACTIVE → schedule-purge is rejected.
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        TenantLifecycleActionRequestDTO req = withReason("nope");
        assertThatThrownBy(() -> service.schedulePurge(hospitalId, req, null))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Cannot schedule purge");
    }

    @Test
    void cancelPurgeRevertsToArchived() {
        hospital.setLifecycleState(HospitalLifecycleState.PENDING_PURGE);
        hospital.setPurgeScheduledFor(Instant.now().plusSeconds(3600));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

        HospitalLifecycleResponseDTO result = service.cancelPurge(hospitalId, null);
        assertThat(result.getLifecycleState()).isEqualTo(HospitalLifecycleState.ARCHIVED);
        assertThat(hospital.getPurgeScheduledFor()).isNull();
    }

    @Test
    void getLifecycleThrowsResourceNotFoundForUnknownHospital() {
        UUID unknown = UUID.randomUUID();
        when(hospitalRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getLifecycle(unknown))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Hospital not found");
    }
}
