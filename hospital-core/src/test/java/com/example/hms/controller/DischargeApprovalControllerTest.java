package com.example.hms.controller;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.payload.dto.discharge.DischargeApprovalDecisionDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalResponseDTO;
import com.example.hms.service.DischargeApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DischargeApprovalControllerTest {

    @Mock
    private DischargeApprovalService service;

    @InjectMocks
    private DischargeApprovalController controller;

    private UUID approvalId;
    private UUID patientId;
    private UUID hospitalId;
    private UUID staffId;
    private DischargeApprovalResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        approvalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        responseDTO = DischargeApprovalResponseDTO.builder()
                .id(approvalId)
                .status(DischargeStatus.PENDING)
                .build();
    }

    // ─── requestDischarge ────────────────────────────────────────

    @Test
    void requestDischargeReturnsOk() {
        DischargeApprovalRequestDTO request = new DischargeApprovalRequestDTO();
        when(service.requestDischarge(request)).thenReturn(responseDTO);

        ResponseEntity<DischargeApprovalResponseDTO> result = controller.requestDischarge(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDTO);
        verify(service).requestDischarge(request);
    }

    // ─── approve ─────────────────────────────────────────────────

    @Test
    void approveReturnsOk() {
        DischargeApprovalDecisionDTO decision = new DischargeApprovalDecisionDTO();
        DischargeApprovalResponseDTO approved = DischargeApprovalResponseDTO.builder()
                .id(approvalId).status(DischargeStatus.APPROVED).build();
        when(service.approve(approvalId, decision)).thenReturn(approved);

        ResponseEntity<DischargeApprovalResponseDTO> result = controller.approve(approvalId, decision);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(approved);
        assertThat(result.getBody().getStatus()).isEqualTo(DischargeStatus.APPROVED);
        verify(service).approve(approvalId, decision);
    }

    // ─── reject ──────────────────────────────────────────────────

    @Test
    void rejectReturnsOk() {
        DischargeApprovalDecisionDTO decision = new DischargeApprovalDecisionDTO();
        DischargeApprovalResponseDTO rejected = DischargeApprovalResponseDTO.builder()
                .id(approvalId).status(DischargeStatus.REJECTED).build();
        when(service.reject(approvalId, decision)).thenReturn(rejected);

        ResponseEntity<DischargeApprovalResponseDTO> result = controller.reject(approvalId, decision);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(rejected);
        assertThat(result.getBody().getStatus()).isEqualTo(DischargeStatus.REJECTED);
        verify(service).reject(approvalId, decision);
    }

    // ─── cancel ──────────────────────────────────────────────────

    @Test
    void cancelWithReasonReturnsOk() {
        DischargeApprovalResponseDTO cancelled = DischargeApprovalResponseDTO.builder()
                .id(approvalId).status(DischargeStatus.CANCELLED).build();
        when(service.cancel(approvalId, staffId, "Patient changed mind")).thenReturn(cancelled);

        ResponseEntity<DischargeApprovalResponseDTO> result =
                controller.cancel(approvalId, staffId, "Patient changed mind");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(cancelled);
        verify(service).cancel(approvalId, staffId, "Patient changed mind");
    }

    @Test
    void cancelWithoutReasonReturnsOk() {
        DischargeApprovalResponseDTO cancelled = DischargeApprovalResponseDTO.builder()
                .id(approvalId).status(DischargeStatus.CANCELLED).build();
        when(service.cancel(approvalId, staffId, null)).thenReturn(cancelled);

        ResponseEntity<DischargeApprovalResponseDTO> result =
                controller.cancel(approvalId, staffId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(cancelled);
        verify(service).cancel(approvalId, staffId, null);
    }

    // ─── getById ─────────────────────────────────────────────────

    @Test
    void getByIdReturnsOk() {
        when(service.getById(approvalId)).thenReturn(responseDTO);

        ResponseEntity<DischargeApprovalResponseDTO> result = controller.getById(approvalId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDTO);
        verify(service).getById(approvalId);
    }

    // ─── getActiveForPatient ─────────────────────────────────────

    @Test
    void getActiveForPatientReturnsOk() {
        List<DischargeApprovalResponseDTO> list = List.of(responseDTO);
        when(service.getActiveForPatient(patientId)).thenReturn(list);

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getActiveForPatient(patientId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        verify(service).getActiveForPatient(patientId);
    }

    @Test
    void getActiveForPatientReturnsEmptyList() {
        when(service.getActiveForPatient(patientId)).thenReturn(Collections.emptyList());

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getActiveForPatient(patientId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }

    // ─── getPendingForHospital ───────────────────────────────────

    @Test
    void getPendingForHospitalReturnsOk() {
        List<DischargeApprovalResponseDTO> list = List.of(responseDTO);
        when(service.getPendingForHospital(hospitalId)).thenReturn(list);

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getPendingForHospital(hospitalId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        verify(service).getPendingForHospital(hospitalId);
    }

    @Test
    void getPendingForHospitalReturnsEmptyList() {
        when(service.getPendingForHospital(hospitalId)).thenReturn(Collections.emptyList());

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getPendingForHospital(hospitalId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }

    // ─── getByHospital ───────────────────────────────────────────

    @Test
    void getByHospitalWithStatusReturnsOk() {
        List<DischargeApprovalResponseDTO> list = List.of(responseDTO);
        when(service.getByHospitalAndStatus(hospitalId, DischargeStatus.PENDING)).thenReturn(list);

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getByHospital(hospitalId, DischargeStatus.PENDING);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        verify(service).getByHospitalAndStatus(hospitalId, DischargeStatus.PENDING);
    }

    @Test
    void getByHospitalWithoutStatusReturnsOk() {
        List<DischargeApprovalResponseDTO> list = List.of(responseDTO);
        when(service.getByHospitalAndStatus(hospitalId, null)).thenReturn(list);

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getByHospital(hospitalId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        verify(service).getByHospitalAndStatus(hospitalId, null);
    }

    @Test
    void getByHospitalReturnsEmptyList() {
        when(service.getByHospitalAndStatus(hospitalId, DischargeStatus.APPROVED))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<DischargeApprovalResponseDTO>> result =
                controller.getByHospital(hospitalId, DischargeStatus.APPROVED);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }
}
