package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.payload.dto.superadmin.SecurityRuleSetRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSetResponseDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleSimulationResultDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityRuleTemplateImportResponseDTO;
import com.example.hms.service.SecurityRuleGovernanceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class SuperAdminSecurityRuleControllerTest {

    @Mock
    private SecurityRuleGovernanceService securityRuleGovernanceService;

    @InjectMocks
    private SuperAdminSecurityRuleController controller;

    @Test
    void createRuleSetReturnsCreated() {
        SecurityRuleSetRequestDTO request = new SecurityRuleSetRequestDTO();
        request.setName("Adaptive guardrails");

        SecurityRuleSetResponseDTO responseDto = SecurityRuleSetResponseDTO.builder()
            .id(UUID.randomUUID().toString())
            .code("ADAPTIVE-GUARDRAILS")
            .name("Adaptive guardrails")
            .ruleCount(2)
            .build();

        when(securityRuleGovernanceService.createRuleSet(request)).thenReturn(responseDto);

        ResponseEntity<SecurityRuleSetResponseDTO> response = controller.createRuleSet(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(securityRuleGovernanceService).createRuleSet(request);
    }

    @Test
    void listTemplatesReturnsServicePayload() {
        List<SecurityRuleTemplateDTO> templates = List.of(
            SecurityRuleTemplateDTO.builder()
                .code("RBAC")
                .title("RBAC")
                .defaultRules(List.of())
                .build()
        );
        when(securityRuleGovernanceService.listTemplates()).thenReturn(templates);

        ResponseEntity<List<SecurityRuleTemplateDTO>> response = controller.listTemplates();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(templates);
        verify(securityRuleGovernanceService).listTemplates();
    }

    @Test
    void importTemplateReturnsCreated() {
        SecurityRuleTemplateImportRequestDTO request = new SecurityRuleTemplateImportRequestDTO();
        request.setTemplateCode("RBAC");

        SecurityRuleTemplateImportResponseDTO responseDto = SecurityRuleTemplateImportResponseDTO.builder()
            .templateCode("RBAC")
            .importedRuleCount(2)
            .ruleSet(SecurityRuleSetResponseDTO.builder().id(UUID.randomUUID().toString()).build())
            .build();

        when(securityRuleGovernanceService.importTemplate(request)).thenReturn(responseDto);

        ResponseEntity<SecurityRuleTemplateImportResponseDTO> response = controller.importTemplate(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(securityRuleGovernanceService).importTemplate(request);
    }

    @Test
    void simulatePolicyImpactReturnsOk() {
        SecurityRuleSimulationRequestDTO request = new SecurityRuleSimulationRequestDTO();
        request.setScenario("High-risk rollout");

        SecurityRuleSimulationResultDTO result = SecurityRuleSimulationResultDTO.builder()
            .scenario("High-risk rollout")
            .evaluatedRuleCount(1)
            .impactScore(3.2)
            .impactedControllers(List.of("RoleController"))
            .recommendedActions(List.of("Notify teams"))
            .evaluatedAt(OffsetDateTime.now())
            .build();

        when(securityRuleGovernanceService.simulatePolicyImpact(request)).thenReturn(result);

        ResponseEntity<SecurityRuleSimulationResultDTO> response = controller.simulatePolicyImpact(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(result);
        verify(securityRuleGovernanceService).simulatePolicyImpact(request);
    }
}
