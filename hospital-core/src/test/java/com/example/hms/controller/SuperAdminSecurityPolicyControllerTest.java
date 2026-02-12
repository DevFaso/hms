package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.payload.dto.superadmin.SecurityPolicyApprovalSummaryDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineExportDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineResponseDTO;
import com.example.hms.service.SecurityPolicyGovernanceService;
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
class SuperAdminSecurityPolicyControllerTest {

    @Mock
    private SecurityPolicyGovernanceService securityPolicyGovernanceService;

    @InjectMocks
    private SuperAdminSecurityPolicyController controller;

    @Test
    void createBaselineReturnsCreatedResponse() {
        SecurityPolicyBaselineRequestDTO request = new SecurityPolicyBaselineRequestDTO();
        request.setTitle("Baseline");
        request.setEnforcementLevel("GLOBAL");

        SecurityPolicyBaselineResponseDTO responseDto = SecurityPolicyBaselineResponseDTO.builder()
            .id(UUID.randomUUID())
            .baselineVersion("baseline-2025")
            .title("Baseline")
            .policyCount(4)
            .enforcementLevel("GLOBAL")
            .build();

        when(securityPolicyGovernanceService.createBaseline(request)).thenReturn(responseDto);

        ResponseEntity<SecurityPolicyBaselineResponseDTO> response = controller.createBaseline(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(securityPolicyGovernanceService).createBaseline(request);
    }

    @Test
    void listPendingApprovalsReturnsServiceResult() {
        List<SecurityPolicyApprovalSummaryDTO> approvals = List.of(
            SecurityPolicyApprovalSummaryDTO.builder()
                .id(UUID.randomUUID())
                .policyName("Data retention")
                .build()
        );

        when(securityPolicyGovernanceService.listPendingApprovals()).thenReturn(approvals);

        ResponseEntity<List<SecurityPolicyApprovalSummaryDTO>> response = controller.listPendingApprovals();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(approvals);
        verify(securityPolicyGovernanceService).listPendingApprovals();
    }

    @Test
    void exportLatestBaselineReturnsServiceResult() {
        SecurityPolicyBaselineExportDTO exportDTO = SecurityPolicyBaselineExportDTO.builder()
            .baselineVersion("baseline-2025")
            .fileName("baseline.json")
            .contentType("application/json")
            .base64Content("e30=")
            .generatedAt(OffsetDateTime.now())
            .build();

        when(securityPolicyGovernanceService.exportLatestBaseline()).thenReturn(exportDTO);

        ResponseEntity<SecurityPolicyBaselineExportDTO> response = controller.exportLatestBaseline();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(exportDTO);
        verify(securityPolicyGovernanceService).exportLatestBaseline();
    }
}
