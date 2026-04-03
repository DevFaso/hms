package com.example.hms.controller;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.LabTestDefinitionApprovalRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.service.LabTestDefinitionService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Direct unit tests for {@link LabTestDefinitionController}.
 *
 * Tests the controller-layer logic (routing, response building) without the full
 * HTTP stack, bypassing filter and security infrastructure that is already tested
 * at the {@code @WebMvcTest} / integration level.
 */
@ExtendWith(MockitoExtension.class)
class LabTestDefinitionControllerTest {

    @Mock
    private LabTestDefinitionService service;

    @InjectMocks
    private LabTestDefinitionController controller;

    private static final UUID DEF_ID = UUID.randomUUID();

    // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private LabTestDefinitionApprovalRequestDTO approvalReq(String action) {
        return approvalReq(action, null);
    }

    private LabTestDefinitionApprovalRequestDTO approvalReq(String action, String reason) {
        return LabTestDefinitionApprovalRequestDTO.builder()
                .action(action)
                .rejectionReason(reason)
                .build();
    }

    private LabTestDefinitionResponseDTO approvalResp(LabTestDefinitionApprovalStatus status) {
        return LabTestDefinitionResponseDTO.builder()
                .id(DEF_ID)
                .testCode("CBC")
                .name("Complete Blood Count")
                .approvalStatus(status.name())
                .active(true)
                .build();
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // POST /lab-test-definitions/{id}/approval â€” happy paths
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void processApproval_submitForQa_returns200() {
        LabTestDefinitionApprovalRequestDTO req = approvalReq("SUBMIT_FOR_QA");
        LabTestDefinitionResponseDTO resp = approvalResp(LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW);
        when(service.processApprovalAction(eq(DEF_ID), any())).thenReturn(resp);

        ResponseEntity<?> result = controller.processApproval(DEF_ID, req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void processApproval_approve_returns200() {
        LabTestDefinitionApprovalRequestDTO req = approvalReq("APPROVE");
        LabTestDefinitionResponseDTO resp = approvalResp(LabTestDefinitionApprovalStatus.APPROVED);
        when(service.processApprovalAction(eq(DEF_ID), any())).thenReturn(resp);

        ResponseEntity<?> result = controller.processApproval(DEF_ID, req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void processApproval_reject_returns200() {
        LabTestDefinitionApprovalRequestDTO req = approvalReq("REJECT", "Missing reference range");
        LabTestDefinitionResponseDTO resp = approvalResp(LabTestDefinitionApprovalStatus.REJECTED);
        when(service.processApprovalAction(eq(DEF_ID), any())).thenReturn(resp);

        ResponseEntity<?> result = controller.processApproval(DEF_ID, req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void processApproval_retire_returns200() {
        LabTestDefinitionApprovalRequestDTO req = approvalReq("RETIRE");
        LabTestDefinitionResponseDTO resp = approvalResp(LabTestDefinitionApprovalStatus.RETIRED);
        when(service.processApprovalAction(eq(DEF_ID), any())).thenReturn(resp);

        ResponseEntity<?> result = controller.processApproval(DEF_ID, req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void processApproval_activate_returns200() {
        LabTestDefinitionApprovalRequestDTO req = approvalReq("ACTIVATE");
        LabTestDefinitionResponseDTO resp = approvalResp(LabTestDefinitionApprovalStatus.ACTIVE);
        when(service.processApprovalAction(eq(DEF_ID), any())).thenReturn(resp);

        ResponseEntity<?> result = controller.processApproval(DEF_ID, req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // POST /lab-test-definitions/{id}/approval â€” service throws propagated
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void processApproval_wrongStatus_propagatesIllegalState() {
        when(service.processApprovalAction(eq(DEF_ID), any()))
                .thenThrow(new IllegalStateException("Action not allowed from status: DRAFT"));

        assertThatThrownBy(() -> controller.processApproval(DEF_ID, approvalReq("APPROVE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Action not allowed from status");
    }

    @Test
    void processApproval_insufficientRole_propagatesAccessDenied() {
        when(service.processApprovalAction(eq(DEF_ID), any()))
                .thenThrow(new AccessDeniedException("Insufficient role"));

        assertThatThrownBy(() -> controller.processApproval(DEF_ID, approvalReq("APPROVE")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void processApproval_definitionNotFound_propagatesEntityNotFound() {
        when(service.processApprovalAction(eq(DEF_ID), any()))
                .thenThrow(new EntityNotFoundException("Lab Test Definition not found"));

        assertThatThrownBy(() -> controller.processApproval(DEF_ID, approvalReq("SUBMIT_FOR_QA")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // GET /lab-test-definitions/search â€” approvalStatus filter routed
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    void search_withApprovalStatusFilter_delegatesToService() {
        LabTestDefinitionResponseDTO dto = approvalResp(LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW);
        Page<LabTestDefinitionResponseDTO> page = new PageImpl<>(List.of(dto));
        when(service.search(any(), any(), any(), any(),
                eq(LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW), any()))
                .thenReturn(page);

        ResponseEntity<?> result = controller.search(
                null, null, null, null,
                LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW,
                Pageable.ofSize(20));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void search_withoutFilter_returnsEmptyPage() {
        Page<LabTestDefinitionResponseDTO> empty = Page.empty();
        when(service.search(any(), any(), any(), any(), any(), any())).thenReturn(empty);

        ResponseEntity<?> result = controller.search(null, null, null, null, null, Pageable.unpaged());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
