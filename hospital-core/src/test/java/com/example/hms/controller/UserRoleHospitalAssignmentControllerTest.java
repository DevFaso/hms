package com.example.hms.controller;

import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBatchResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResultDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentMultiRequestDTO;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.service.UserRoleHospitalAssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserRoleHospitalAssignmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserRoleHospitalAssignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRoleHospitalAssignmentService assignmentService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void assignAcrossMultipleScopesReturnsBatchSummary() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        UserRoleAssignmentMultiRequestDTO requestDTO = UserRoleAssignmentMultiRequestDTO.builder()
            .userId(userId)
            .roleId(roleId)
            .hospitalIds(List.of(hospitalId))
            .sendNotifications(true)
            .build();

        UserRoleAssignmentBatchResponseDTO responseDTO = UserRoleAssignmentBatchResponseDTO.builder()
            .requestedAssignments(1)
            .createdAssignments(1)
            .assignments(List.of(UserRoleHospitalAssignmentResponseDTO.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .hospitalId(hospitalId)
                .roleId(roleId)
                .assignmentCode("ABC123")
                .build()))
            .failures(List.of())
            .build();

        when(assignmentService.assignRoleToMultipleScopes(any(UserRoleAssignmentMultiRequestDTO.class)))
            .thenReturn(responseDTO);

        mockMvc.perform(post("/assignments/multi-scope")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.createdAssignments").value(1))
            .andExpect(jsonPath("$.failures").isEmpty());
    }

    @Test
    void regenerateAssignmentCodeReturnsUpdatedAssignment() throws Exception {
        UUID assignmentId = UUID.randomUUID();

        UserRoleHospitalAssignmentResponseDTO responseDTO = UserRoleHospitalAssignmentResponseDTO.builder()
            .id(assignmentId)
            .assignmentCode("NEWCODE")
            .confirmationVerified(false)
            .build();

        when(assignmentService.regenerateAssignmentCode(assignmentId, true))
            .thenReturn(responseDTO);

        mockMvc.perform(post("/assignments/{assignmentId}/regenerate-code", assignmentId)
                .param("resendNotifications", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignmentCode").value("NEWCODE"));
    }

    @Test
    void bulkImportAssignmentsReturnsSummary() throws Exception {
        UserRoleAssignmentBulkImportRequestDTO requestDTO = UserRoleAssignmentBulkImportRequestDTO.builder()
            .csvContent("userId,roleId\n123,456")
            .delimiter(",")
            .skipConflicts(true)
            .build();

        UserRoleAssignmentBulkImportResponseDTO responseDTO = UserRoleAssignmentBulkImportResponseDTO.builder()
            .processed(1)
            .created(1)
            .skipped(0)
            .failed(0)
            .results(List.of(UserRoleAssignmentBulkImportResultDTO.builder()
                .rowNumber(2)
                .identifier("123")
                .success(true)
                .message("created")
                .assignmentId(UUID.randomUUID())
                .assignmentCode("CODE123")
                .build()))
            .build();

        when(assignmentService.bulkImportAssignments(any(UserRoleAssignmentBulkImportRequestDTO.class)))
            .thenReturn(responseDTO);

        mockMvc.perform(post("/assignments/bulk-import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processed").value(1))
            .andExpect(jsonPath("$.created").value(1));
    }
}
