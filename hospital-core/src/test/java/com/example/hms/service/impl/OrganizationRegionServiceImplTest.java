package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationRegion;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.OrganizationRegionResponseDTO;
import com.example.hms.payload.dto.superadmin.OrganizationRegionUpdateRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrganizationRegionServiceImpl (MVP-9)")
class OrganizationRegionServiceImplTest {

    private OrganizationRepository organizationRepository;
    private AuditEventLogService auditEventLogService;
    private OrganizationRegionServiceImpl service;

    @BeforeEach
    void setUp() {
        organizationRepository = mock(OrganizationRepository.class);
        auditEventLogService = mock(AuditEventLogService.class);
        service = new OrganizationRegionServiceImpl(organizationRepository, auditEventLogService);
    }

    private Organization org(String name, String code, OrganizationRegion region) {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setCode(code);
        o.setRegion(region);
        return o;
    }

    @Test
    @DisplayName("listAvailableRegions returns the full enum catalogue")
    void listAvailableRegions_returnsCatalogue() {
        List<OrganizationRegion> regions = service.listAvailableRegions();
        assertThat(regions)
            .containsExactly(OrganizationRegion.values())
            .contains(OrganizationRegion.BF, OrganizationRegion.EU, OrganizationRegion.OTHER);
    }

    @Test
    @DisplayName("listOrganizationRegions returns rows sorted by name")
    void listOrganizationRegions_sortedByName() {
        when(organizationRepository.findAll()).thenReturn(List.of(
            org("Zeta Hospital", "ZHO", OrganizationRegion.US),
            org("Alpha Clinic", "ACL", OrganizationRegion.BF),
            org("midcap Health", "MHL", OrganizationRegion.SN)));

        List<OrganizationRegionResponseDTO> rows = service.listOrganizationRegions();

        assertThat(rows).extracting(OrganizationRegionResponseDTO::getOrganizationName)
            .containsExactly("Alpha Clinic", "midcap Health", "Zeta Hospital");
    }

    @Test
    @DisplayName("getOrganizationRegion throws ResourceNotFoundException when org missing")
    void getOrganizationRegion_unknown_throws() {
        UUID id = UUID.randomUUID();
        when(organizationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrganizationRegion(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateOrganizationRegion changes the region, persists, and emits audit")
    void updateOrganizationRegion_changesAndAudits() {
        Organization existing = org("Beta Clinic", "BCL", OrganizationRegion.BF);
        when(organizationRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        OrganizationRegionResponseDTO result = service.updateOrganizationRegion(
            existing.getId(),
            OrganizationRegionUpdateRequestDTO.builder()
                .region(OrganizationRegion.SN)
                .reason("Tenant relocated to Senegal jurisdiction")
                .build());

        assertThat(result.getRegion()).isEqualTo(OrganizationRegion.SN);
        verify(organizationRepository, times(1)).save(existing);

        ArgumentCaptor<AuditEventRequestDTO> auditCaptor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCaptor.capture());
        AuditEventRequestDTO audit = auditCaptor.getValue();
        assertThat(audit.getEventType()).isEqualTo(AuditEventType.ORGANIZATION_REGION_UPDATED);
        assertThat(audit.getResourceId()).isEqualTo(existing.getId().toString());
        assertThat(audit.getEventDescription())
            .contains("BF").contains("SN").contains("Senegal jurisdiction");
    }

    @Test
    @DisplayName("updateOrganizationRegion is a noop save when region is unchanged but still audits")
    void updateOrganizationRegion_unchanged_skipsSaveButAudits() {
        Organization existing = org("Gamma Hospital", "GHL", OrganizationRegion.BF);
        when(organizationRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.updateOrganizationRegion(
            existing.getId(),
            OrganizationRegionUpdateRequestDTO.builder()
                .region(OrganizationRegion.BF)
                .build());

        verify(organizationRepository, never()).save(any(Organization.class));
        ArgumentCaptor<AuditEventRequestDTO> auditCaptor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventDescription()).contains("reaffirmed");
    }

    @Test
    @DisplayName("updateOrganizationRegion swallows audit failures so the update succeeds")
    void updateOrganizationRegion_auditFailure_doesNotPropagate() {
        Organization existing = org("Delta Hospital", "DHL", OrganizationRegion.BF);
        when(organizationRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(auditEventLogService.logEvent(any(AuditEventRequestDTO.class)))
            .thenThrow(new RuntimeException("audit-store-down"));

        OrganizationRegionResponseDTO result = service.updateOrganizationRegion(
            existing.getId(),
            OrganizationRegionUpdateRequestDTO.builder()
                .region(OrganizationRegion.EU)
                .reason("GDPR migration")
                .build());

        assertThat(result.getRegion()).isEqualTo(OrganizationRegion.EU);
        verify(organizationRepository, times(1)).save(existing);
    }
}
