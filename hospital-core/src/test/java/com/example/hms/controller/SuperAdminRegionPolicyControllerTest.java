package com.example.hms.controller;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.payload.dto.superadmin.RegionPolicyCapabilitiesDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyResponseDTO;
import com.example.hms.payload.dto.superadmin.RegionPolicyUpdateRequestDTO;
import com.example.hms.service.RegionPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminRegionPolicyControllerTest {

    @Mock
    private RegionPolicyService service;

    @InjectMocks
    private SuperAdminRegionPolicyController controller;

    @Test
    void listAllForwardsToService() {
        List<RegionPolicyResponseDTO> rows = List.of(
            RegionPolicyResponseDTO.builder().region(OrganizationRegion.BF).build());
        when(service.listAll()).thenReturn(rows);

        ResponseEntity<List<RegionPolicyResponseDTO>> response = controller.listAll();

        assertThat(response.getBody()).isSameAs(rows);
        verify(service).listAll();
    }

    @Test
    void getOneRegionForwardsToService() {
        RegionPolicyResponseDTO row = RegionPolicyResponseDTO.builder()
            .region(OrganizationRegion.EU).build();
        when(service.get(OrganizationRegion.EU)).thenReturn(row);

        ResponseEntity<RegionPolicyResponseDTO> response = controller.get(OrganizationRegion.EU);

        assertThat(response.getBody()).isSameAs(row);
    }

    @Test
    void updateForwardsRequestToService() {
        RegionPolicyUpdateRequestDTO request = RegionPolicyUpdateRequestDTO.builder()
            .retentionDays(365).build();
        RegionPolicyResponseDTO updated = RegionPolicyResponseDTO.builder()
            .region(OrganizationRegion.BF).retentionDays(365).build();
        when(service.update(OrganizationRegion.BF, request)).thenReturn(updated);

        ResponseEntity<RegionPolicyResponseDTO> response = controller.update(
            OrganizationRegion.BF, request);

        assertThat(response.getBody()).isSameAs(updated);
        verify(service).update(OrganizationRegion.BF, request);
    }

    @Test
    void capabilitiesReturnsTheCapabilityFlagFromTheService() {
        // MVP-c3 — the editor reads this so it can disable the
        // deployment-URL column when only the stub provisioning
        // client is wired.
        when(service.isRemoteProvisioningCapable()).thenReturn(false);

        ResponseEntity<RegionPolicyCapabilitiesDTO> response = controller.capabilities();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isRemoteProvisioningCapable()).isFalse();
    }

    @Test
    void capabilitiesReturnsTrueWhenRealClientIsWired() {
        when(service.isRemoteProvisioningCapable()).thenReturn(true);

        ResponseEntity<RegionPolicyCapabilitiesDTO> response = controller.capabilities();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isRemoteProvisioningCapable()).isTrue();
    }
}
