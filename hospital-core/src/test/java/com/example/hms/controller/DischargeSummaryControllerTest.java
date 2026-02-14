package com.example.hms.controller;

import com.example.hms.payload.dto.discharge.DischargeSummaryRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.service.DischargeSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DischargeSummaryControllerTest {

    @Mock private DischargeSummaryService dischargeSummaryService;
    @InjectMocks private DischargeSummaryController controller;

    private Locale locale;
    private DischargeSummaryRequestDTO request;
    private DischargeSummaryResponseDTO response;
    private UUID id;
    private UUID hospitalId;
    private UUID patientId;
    private UUID providerId;
    private UUID encounterId;

    @BeforeEach
    void setUp() {
        locale = Locale.ENGLISH;
        id = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        providerId = UUID.randomUUID();
        encounterId = UUID.randomUUID();
        request = new DischargeSummaryRequestDTO();
        response = new DischargeSummaryResponseDTO();
    }

    // ─── createDischargeSummary ──────────────────────────────────

    @Test
    void createDischargeSummaryReturnsCreated() {
        when(dischargeSummaryService.createDischargeSummary(request, locale)).thenReturn(response);

        ResponseEntity<DischargeSummaryResponseDTO> result = controller.createDischargeSummary(request, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(response);
        verify(dischargeSummaryService).createDischargeSummary(request, locale);
    }

    // ─── updateDischargeSummary ──────────────────────────────────

    @Test
    void updateDischargeSummaryReturnsOk() {
        when(dischargeSummaryService.updateDischargeSummary(id, request, locale)).thenReturn(response);

        ResponseEntity<DischargeSummaryResponseDTO> result = controller.updateDischargeSummary(id, request, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
        verify(dischargeSummaryService).updateDischargeSummary(id, request, locale);
    }

    // ─── finalizeDischargeSummary ────────────────────────────────

    @Test
    void finalizeDischargeSummaryReturnsOk() {
        String providerSignature = "Dr. Smith";
        when(dischargeSummaryService.finalizeDischargeSummary(id, providerSignature, providerId, locale))
                .thenReturn(response);

        ResponseEntity<DischargeSummaryResponseDTO> result =
                controller.finalizeDischargeSummary(id, providerSignature, providerId, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
        verify(dischargeSummaryService).finalizeDischargeSummary(id, providerSignature, providerId, locale);
    }

    // ─── getDischargeSummaryById ─────────────────────────────────

    @Test
    void getDischargeSummaryByIdReturnsOk() {
        when(dischargeSummaryService.getDischargeSummaryById(id, locale)).thenReturn(response);

        ResponseEntity<DischargeSummaryResponseDTO> result = controller.getDischargeSummaryById(id, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    // ─── getDischargeSummaryByEncounter ──────────────────────────

    @Test
    void getDischargeSummaryByEncounterReturnsOk() {
        when(dischargeSummaryService.getDischargeSummaryByEncounter(encounterId, locale)).thenReturn(response);

        ResponseEntity<DischargeSummaryResponseDTO> result =
                controller.getDischargeSummaryByEncounter(encounterId, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    // ─── getDischargeSummariesByPatient ──────────────────────────

    @Test
    void getDischargeSummariesByPatientReturnsOk() {
        List<DischargeSummaryResponseDTO> list = List.of(response);
        when(dischargeSummaryService.getDischargeSummariesByPatient(patientId, locale)).thenReturn(list);

        ResponseEntity<List<DischargeSummaryResponseDTO>> result =
                controller.getDischargeSummariesByPatient(patientId, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
    }

    // ─── getDischargeSummariesByHospitalAndDateRange ─────────────

    @Test
    void getDischargeSummariesByHospitalAndDateRangeReturnsOk() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2025, 12, 31);
        List<DischargeSummaryResponseDTO> list = List.of(response);
        when(dischargeSummaryService.getDischargeSummariesByHospitalAndDateRange(hospitalId, start, end, locale))
                .thenReturn(list);

        ResponseEntity<List<DischargeSummaryResponseDTO>> result =
                controller.getDischargeSummariesByHospitalAndDateRange(hospitalId, start, end, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
    }

    // ─── getUnfinalizedDischargeSummaries ────────────────────────

    @Test
    void getUnfinalizedDischargeSummariesReturnsOk() {
        List<DischargeSummaryResponseDTO> list = List.of(response);
        when(dischargeSummaryService.getUnfinalizedDischargeSummaries(hospitalId, locale)).thenReturn(list);

        ResponseEntity<List<DischargeSummaryResponseDTO>> result =
                controller.getUnfinalizedDischargeSummaries(hospitalId, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
    }

    // ─── getDischargeSummariesWithPendingResults ─────────────────

    @Test
    void getDischargeSummariesWithPendingResultsReturnsOk() {
        List<DischargeSummaryResponseDTO> list = List.of(response);
        when(dischargeSummaryService.getDischargeSummariesWithPendingResults(hospitalId, locale)).thenReturn(list);

        ResponseEntity<List<DischargeSummaryResponseDTO>> result =
                controller.getDischargeSummariesWithPendingResults(hospitalId, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
    }

    // ─── getDischargeSummariesByProvider ─────────────────────────

    @Test
    void getDischargeSummariesByProviderReturnsOk() {
        List<DischargeSummaryResponseDTO> list = List.of(response);
        when(dischargeSummaryService.getDischargeSummariesByProvider(providerId, locale)).thenReturn(list);

        ResponseEntity<List<DischargeSummaryResponseDTO>> result =
                controller.getDischargeSummariesByProvider(providerId, locale);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
    }

    // ─── deleteDischargeSummary ──────────────────────────────────

    @Test
    void deleteDischargeSummaryReturnsNoContent() {
        doNothing().when(dischargeSummaryService).deleteDischargeSummary(id, providerId);

        ResponseEntity<Void> result = controller.deleteDischargeSummary(id, providerId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(result.getBody()).isNull();
        verify(dischargeSummaryService).deleteDischargeSummary(id, providerId);
    }
}
