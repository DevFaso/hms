package com.example.hms.service.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.tenant.TenantArchiveEncryptionService;
import com.example.hms.service.tenant.TenantExportPackager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantPurgeExecutorTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private TenantExportPackager exportPackager;
    @Mock private TenantArchiveEncryptionService archiveEncryption;

    @InjectMocks private TenantPurgeExecutor executor;

    private Organization org;

    @BeforeEach
    void setUp() throws IOException {
        ReflectionTestUtils.setField(executor, "outputDir", System.getProperty("java.io.tmpdir"));

        org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Acme Health");
        org.setCode("ACME");
        org.setLifecycleState(OrganizationLifecycleState.PENDING_PURGE);
        org.setActive(false);

        Path archive = Path.of(System.getProperty("java.io.tmpdir"), "stub.zip.enc");
        Path envelope = Path.of(System.getProperty("java.io.tmpdir"), "stub.zip.enc.envelope.json");
        // lenient() — failure-path tests in this class throw before reaching
        // these stubs, which Mockito's STRICT_STUBS would otherwise flag.
        lenient().when(exportPackager.packageOrganization(any(Organization.class), any(Path.class)))
            .thenReturn(new TenantExportPackager.PackageResult(
                Path.of(System.getProperty("java.io.tmpdir"), "stub.zip"),
                Map.of("organization.ndjson", 1L)));
        lenient().when(archiveEncryption.encryptArchive(any(Path.class), any(Path.class)))
            .thenReturn(new TenantArchiveEncryptionService.EncryptionResult(
                archive, envelope,
                TenantArchiveEncryptionService.EncryptionResult.Mode.ENCRYPTED,
                "AES/GCM/NoPadding"));
    }

    @Test
    void executePurgeFlipsStateToPurgedAndStampsPurgedAt() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.executePurge(org, false);

        assertThat(org.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
        assertThat(org.getPurgedAt()).isNotNull();
        assertThat(org.isActive()).isFalse();
        verify(organizationRepository, times(1)).save(org);
    }

    @Test
    void executePurgeEmitsBothPackagedAndPurgedAuditEvents() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.executePurge(org, false);

        ArgumentCaptor<AuditEventRequestDTO> cap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(cap.capture());
        List<AuditEventRequestDTO> events = cap.getAllValues();
        assertThat(events).extracting(AuditEventRequestDTO::getEventType)
            .containsExactly(AuditEventType.TENANT_PURGE_PACKAGED, AuditEventType.TENANT_PURGED);
        assertThat(events.get(0).getResourceId()).isEqualTo(org.getId().toString());
        assertThat(events.get(0).getEventDescription()).contains("packaged");
    }

    @Test
    void executeDeletionFlagDoesNotChangeBehaviourInThisBatch() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.executePurge(org, true);

        // Cascade-delete is still deferred — the executor logs a warning
        // and proceeds with the packaging + state transition only. The
        // archive captures the metadata but no row-level deletion runs.
        verify(organizationRepository, times(1)).save(org);
    }

    @Test
    void packagingFailureAbortsPurgeAndEmitsPackagingFailedAudit() throws IOException {
        when(exportPackager.packageOrganization(any(Organization.class), any(Path.class)))
            .thenThrow(new IOException("disk full"));

        executor.executePurge(org, false);

        // State stays at PENDING_PURGE — the operator can retry next sweep.
        assertThat(org.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PENDING_PURGE);
        verify(organizationRepository, never()).save(any(Organization.class));

        ArgumentCaptor<AuditEventRequestDTO> cap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(1)).logEvent(cap.capture());
        assertThat(cap.getValue().getEventType())
            .isEqualTo(AuditEventType.TENANT_PURGE_PACKAGING_FAILED);
        assertThat(cap.getValue().getEventDescription()).contains("disk full");
    }

    @Test
    void encryptionFailureAlsoAbortsPurge() throws IOException {
        when(archiveEncryption.encryptArchive(any(Path.class), any(Path.class)))
            .thenThrow(new IOException("KEK source not configured"));

        executor.executePurge(org, false);

        assertThat(org.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PENDING_PURGE);
        verify(organizationRepository, never()).save(any(Organization.class));
        verify(auditEventLogService).logEvent(
            argThat(req -> req.getEventType() == AuditEventType.TENANT_PURGE_PACKAGING_FAILED));
    }

    @Test
    void auditFailureDoesNotPropagateOutOfTheExecutor() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditEventLogService.logEvent(any())).thenThrow(new RuntimeException("audit down"));

        // Should not throw — audit emission is best-effort by contract.
        executor.executePurge(org, false);

        assertThat(org.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
    }

}
