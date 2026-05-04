package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationRegion;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.platform.RegionPolicy;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyResponseDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyUpdateRequestDTO;
import com.example.hms.repository.platform.RegionPolicyRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegionPolicyServiceImplTest {

    @Mock private RegionPolicyRepository regionPolicyRepository;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks private RegionPolicyServiceImpl service;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(actorId)
            .principalUsername("super.admin")
            .superAdmin(true)
            .permittedOrganizationIds(Set.of())
            .build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    private RegionPolicy seedRow(OrganizationRegion region, Integer retention, String export, String url) {
        return RegionPolicy.builder()
            .region(region)
            .retentionDays(retention)
            .defaultExportFormat(export)
            .targetDeploymentUrl(url)
            .updatedAt(Instant.now())
            .updatedBy("system")
            .build();
    }

    @Test
    void listAllReturnsRowsSortedByRegionCode() {
        when(regionPolicyRepository.findAll()).thenReturn(List.of(
            seedRow(OrganizationRegion.SN, null, null, null),
            seedRow(OrganizationRegion.BF, 365, null, null),
            seedRow(OrganizationRegion.EU, null, "GDPR_PORTABILITY", null)));

        List<RegionPolicyResponseDTO> result = service.listAll();

        assertThat(result).extracting(RegionPolicyResponseDTO::getRegion)
            .containsExactly(OrganizationRegion.BF, OrganizationRegion.EU, OrganizationRegion.SN);
    }

    @Test
    void getReturnsSnapshotForKnownRegion() {
        when(regionPolicyRepository.findById(OrganizationRegion.EU))
            .thenReturn(Optional.of(seedRow(OrganizationRegion.EU, null, "GDPR_PORTABILITY", null)));

        RegionPolicyResponseDTO result = service.get(OrganizationRegion.EU);
        assertThat(result.getDefaultExportFormat()).isEqualTo("GDPR_PORTABILITY");
    }

    @Test
    void getThrowsResourceNotFoundForUnseededRegion() {
        when(regionPolicyRepository.findById(OrganizationRegion.OTHER))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OrganizationRegion.OTHER))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not seeded");
    }

    @Test
    void updateAppliesOverridesAndEmitsAudit() {
        RegionPolicy current = seedRow(OrganizationRegion.BF, null, null, null);
        when(regionPolicyRepository.findById(OrganizationRegion.BF))
            .thenReturn(Optional.of(current));
        when(regionPolicyRepository.save(any(RegionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        RegionPolicyUpdateRequestDTO request = RegionPolicyUpdateRequestDTO.builder()
            .retentionDays(365)
            .defaultExportFormat("GDPR_PORTABILITY")
            .targetDeploymentUrl("https://hms-eu.railway.app")
            .build();

        RegionPolicyResponseDTO result = service.update(OrganizationRegion.BF, request);

        assertThat(result.getRetentionDays()).isEqualTo(365);
        assertThat(result.getDefaultExportFormat()).isEqualTo("GDPR_PORTABILITY");
        assertThat(result.getTargetDeploymentUrl()).isEqualTo("https://hms-eu.railway.app");
        assertThat(result.getUpdatedBy()).isEqualTo("super.admin");

        ArgumentCaptor<AuditEventRequestDTO> cap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AuditEventType.REGION_POLICY_UPDATED);
        assertThat(cap.getValue().getResourceId()).isEqualTo("BF");
    }

    @Test
    void updateWithNullFieldsClearsOverrides() {
        RegionPolicy current = seedRow(OrganizationRegion.EU, 365, "GDPR_PORTABILITY", "https://eu");
        when(regionPolicyRepository.findById(OrganizationRegion.EU))
            .thenReturn(Optional.of(current));
        when(regionPolicyRepository.save(any(RegionPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        RegionPolicyResponseDTO result = service.update(OrganizationRegion.EU, null);

        assertThat(result.getRetentionDays()).isNull();
        assertThat(result.getDefaultExportFormat()).isNull();
        assertThat(result.getTargetDeploymentUrl()).isNull();
        verify(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    void noopUpdateDoesNotSaveOrEmitAudit() {
        RegionPolicy current = seedRow(OrganizationRegion.BF, 30, "STANDARD", null);
        java.time.Instant originalUpdatedAt = current.getUpdatedAt();
        String originalUpdatedBy = current.getUpdatedBy();
        when(regionPolicyRepository.findById(OrganizationRegion.BF))
            .thenReturn(Optional.of(current));

        // Re-applying the same values must not save (Copilot review fix —
        // previously updatedAt/updatedBy bumped on every PUT) and must
        // not emit an audit event.
        RegionPolicyUpdateRequestDTO same = RegionPolicyUpdateRequestDTO.builder()
            .retentionDays(30)
            .defaultExportFormat("STANDARD")
            .targetDeploymentUrl(null)
            .build();
        service.update(OrganizationRegion.BF, same);

        verify(regionPolicyRepository, never()).save(any(RegionPolicy.class));
        verify(auditEventLogService, never()).logEvent(any(AuditEventRequestDTO.class));
        // updatedAt / updatedBy must remain at the pre-call values.
        assertThat(current.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(current.getUpdatedBy()).isEqualTo(originalUpdatedBy);
    }

    @Test
    void resolveRetentionDaysReturnsOverrideOrNull() {
        when(regionPolicyRepository.findById(OrganizationRegion.BF))
            .thenReturn(Optional.of(seedRow(OrganizationRegion.BF, 30, null, null)));
        when(regionPolicyRepository.findById(OrganizationRegion.SN))
            .thenReturn(Optional.empty());

        assertThat(service.resolveRetentionDays(OrganizationRegion.BF)).isEqualTo(30);
        assertThat(service.resolveRetentionDays(OrganizationRegion.SN)).isNull();
        assertThat(service.resolveRetentionDays(null)).isNull();
    }
}
