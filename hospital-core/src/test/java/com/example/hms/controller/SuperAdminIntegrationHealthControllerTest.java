package com.example.hms.controller;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.superadmin.IntegrationHealthRowDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthSummaryDTO;
import com.example.hms.service.SuperAdminIntegrationHealthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminIntegrationHealthControllerTest {

    @Mock
    private SuperAdminIntegrationHealthService service;

    @InjectMocks
    private SuperAdminIntegrationHealthController controller;

    @Test
    void getInventoryReturnsServiceResponse() {
        IntegrationHealthSummaryDTO summary = IntegrationHealthSummaryDTO.builder()
            .totalIntegrations(2)
            .healthyCount(1)
            .failingCount(1)
            .integrations(List.of(
                IntegrationHealthRowDTO.builder()
                    .integrationId("eligibility")
                    .displayName("Insurance eligibility & prior-auth")
                    .rolledUpStatus(IntegrationHealthStatus.HEALTHY)
                    .build()))
            .build();
        when(service.getInventory()).thenReturn(summary);

        ResponseEntity<IntegrationHealthSummaryDTO> response = controller.getInventory();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(summary);
        verify(service).getInventory();
    }

    @Test
    void getIntegrationReturnsServiceResponse() {
        IntegrationHealthRowDTO row = IntegrationHealthRowDTO.builder()
            .integrationId("eligibility")
            .displayName("Insurance eligibility & prior-auth")
            .rolledUpStatus(IntegrationHealthStatus.HEALTHY)
            .build();
        when(service.getIntegration("eligibility")).thenReturn(row);

        ResponseEntity<IntegrationHealthRowDTO> response = controller.getIntegration("eligibility");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(row);
    }

    @Test
    void getIntegrationPropagatesNotFound() {
        when(service.getIntegration("does-not-exist"))
            .thenThrow(new ResourceNotFoundException("integration.health.notfound", "does-not-exist"));

        assertThatThrownBy(() -> controller.getIntegration("does-not-exist"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
