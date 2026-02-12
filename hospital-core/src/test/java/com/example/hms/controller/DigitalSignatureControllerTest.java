package com.example.hms.controller;

import com.example.hms.enums.SignatureType;
import com.example.hms.payload.dto.signature.*;
import com.example.hms.service.signature.DigitalSignatureService;
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
class DigitalSignatureControllerTest {

    @Mock
    private DigitalSignatureService signatureService;

    @InjectMocks
    private DigitalSignatureController controller;

    private UUID signatureId;
    private UUID reportId;
    private UUID providerId;
    private SignatureResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        signatureId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        providerId = UUID.randomUUID();
        responseDTO = SignatureResponseDTO.builder().id(signatureId).build();
    }

    // ─── signReport ──────────────────────────────────────────────

    @Test
    void signReportReturnsCreated() {
        SignatureRequestDTO request = new SignatureRequestDTO();
        when(signatureService.signReport(request)).thenReturn(responseDTO);

        ResponseEntity<SignatureResponseDTO> result = controller.signReport(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(responseDTO);
        verify(signatureService).signReport(request);
    }

    // ─── verifySignature ─────────────────────────────────────────

    @Test
    void verifySignatureReturnsOk() {
        SignatureVerificationRequestDTO request = new SignatureVerificationRequestDTO();
        SignatureVerificationResponseDTO verifyResponse = SignatureVerificationResponseDTO.builder()
                .isValid(true).build();
        when(signatureService.verifySignature(request)).thenReturn(verifyResponse);

        ResponseEntity<SignatureVerificationResponseDTO> result = controller.verifySignature(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(verifyResponse);
        verify(signatureService).verifySignature(request);
    }

    // ─── revokeSignature ─────────────────────────────────────────

    @Test
    void revokeSignatureReturnsOk() {
        SignatureRevocationRequestDTO request = new SignatureRevocationRequestDTO();
        when(signatureService.revokeSignature(signatureId, request)).thenReturn(responseDTO);

        ResponseEntity<SignatureResponseDTO> result = controller.revokeSignature(signatureId, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDTO);
        verify(signatureService).revokeSignature(signatureId, request);
    }

    // ─── getSignaturesByReport ───────────────────────────────────

    @Test
    void getSignaturesByReportReturnsOk() {
        List<SignatureResponseDTO> signatures = List.of(responseDTO);
        when(signatureService.getSignaturesByReportId(SignatureType.DISCHARGE_SUMMARY, reportId))
                .thenReturn(signatures);

        ResponseEntity<List<SignatureResponseDTO>> result =
                controller.getSignaturesByReport(SignatureType.DISCHARGE_SUMMARY, reportId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0)).isEqualTo(responseDTO);
        verify(signatureService).getSignaturesByReportId(SignatureType.DISCHARGE_SUMMARY, reportId);
    }

    @Test
    void getSignaturesByReportReturnsEmptyList() {
        when(signatureService.getSignaturesByReportId(SignatureType.LAB_RESULT, reportId))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SignatureResponseDTO>> result =
                controller.getSignaturesByReport(SignatureType.LAB_RESULT, reportId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }

    // ─── getSignaturesByProvider ─────────────────────────────────

    @Test
    void getSignaturesByProviderReturnsOk() {
        List<SignatureResponseDTO> signatures = List.of(responseDTO);
        when(signatureService.getSignaturesByProvider(providerId)).thenReturn(signatures);

        ResponseEntity<List<SignatureResponseDTO>> result =
                controller.getSignaturesByProvider(providerId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        verify(signatureService).getSignaturesByProvider(providerId);
    }

    @Test
    void getSignaturesByProviderReturnsEmptyList() {
        when(signatureService.getSignaturesByProvider(providerId))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SignatureResponseDTO>> result =
                controller.getSignaturesByProvider(providerId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }

    // ─── getSignatureById ────────────────────────────────────────

    @Test
    void getSignatureByIdReturnsOk() {
        when(signatureService.getSignatureById(signatureId)).thenReturn(responseDTO);

        ResponseEntity<SignatureResponseDTO> result = controller.getSignatureById(signatureId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDTO);
        verify(signatureService).getSignatureById(signatureId);
    }

    // ─── getSignatureAuditTrail ──────────────────────────────────

    @Test
    void getSignatureAuditTrailReturnsOk() {
        SignatureAuditEntryDTO auditEntry = SignatureAuditEntryDTO.builder()
                .action("SIGNED").build();
        List<SignatureAuditEntryDTO> auditTrail = List.of(auditEntry);
        when(signatureService.getSignatureAuditTrail(signatureId)).thenReturn(auditTrail);

        ResponseEntity<List<SignatureAuditEntryDTO>> result =
                controller.getSignatureAuditTrail(signatureId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).getAction()).isEqualTo("SIGNED");
        verify(signatureService).getSignatureAuditTrail(signatureId);
    }

    @Test
    void getSignatureAuditTrailReturnsEmptyList() {
        when(signatureService.getSignatureAuditTrail(signatureId))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SignatureAuditEntryDTO>> result =
                controller.getSignatureAuditTrail(signatureId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }

    // ─── isReportSigned ──────────────────────────────────────────

    @Test
    void isReportSignedReturnsTrueWhenSigned() {
        when(signatureService.isReportSigned(SignatureType.DISCHARGE_SUMMARY, reportId))
                .thenReturn(true);

        ResponseEntity<Boolean> result =
                controller.isReportSigned(SignatureType.DISCHARGE_SUMMARY, reportId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isTrue();
    }

    @Test
    void isReportSignedReturnsFalseWhenNotSigned() {
        when(signatureService.isReportSigned(SignatureType.IMAGING_REPORT, reportId))
                .thenReturn(false);

        ResponseEntity<Boolean> result =
                controller.isReportSigned(SignatureType.IMAGING_REPORT, reportId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isFalse();
    }
}
