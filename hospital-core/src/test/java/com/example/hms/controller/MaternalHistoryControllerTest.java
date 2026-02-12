package com.example.hms.controller;

import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.MaternalHistoryResponseDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.MaternalHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaternalHistoryControllerTest {

    @Mock
    private MaternalHistoryService maternalHistoryService;

    @InjectMocks
    private MaternalHistoryController maternalHistoryController;

    private Authentication authentication;
    private String username;
    private UUID patientId;
    private UUID hospitalId;
    private UUID historyId;
    private MaternalHistoryRequestDTO requestDTO;
    private MaternalHistoryResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        username = "doctor1";
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        historyId = UUID.randomUUID();

        CustomUserDetails userDetails = new CustomUserDetails(
                UUID.randomUUID(),
                username,
                "password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR"))
        );
        authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities()
        );

        requestDTO = MaternalHistoryRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .recordedDate(LocalDateTime.now())
                .dataComplete(true)
                .build();

        responseDTO = MaternalHistoryResponseDTO.builder()
                .id(historyId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .versionNumber(1)
                .dataComplete(true)
                .build();
    }

    @Test
    void createMaternalHistory_shouldReturnCreatedStatus() {
        when(maternalHistoryService.createMaternalHistory(any(MaternalHistoryRequestDTO.class), eq(username)))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .createMaternalHistory(requestDTO, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(historyId);
        verify(maternalHistoryService).createMaternalHistory(requestDTO, username);
    }

    @Test
    void updateMaternalHistory_shouldReturnOkStatus() {
        when(maternalHistoryService.updateMaternalHistory(eq(historyId), any(MaternalHistoryRequestDTO.class), eq(username)))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .updateMaternalHistory(historyId, requestDTO, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(historyId);
        verify(maternalHistoryService).updateMaternalHistory(historyId, requestDTO, username);
    }

    @Test
    void getMaternalHistoryById_shouldReturnHistory() {
        when(maternalHistoryService.getMaternalHistoryById(historyId, username))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .getMaternalHistoryById(historyId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(historyId);
        verify(maternalHistoryService).getMaternalHistoryById(historyId, username);
    }

    @Test
    void getCurrentMaternalHistory_shouldReturnCurrentVersion() {
        when(maternalHistoryService.getCurrentMaternalHistoryByPatientId(patientId, username))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .getCurrentMaternalHistory(patientId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPatientId()).isEqualTo(patientId);
        verify(maternalHistoryService).getCurrentMaternalHistoryByPatientId(patientId, username);
    }

    @Test
    void getAllVersionsByPatient_shouldReturnAllVersions() {
        List<MaternalHistoryResponseDTO> versions = List.of(responseDTO);
        when(maternalHistoryService.getAllVersionsByPatientId(patientId, username))
                .thenReturn(versions);

        ResponseEntity<List<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .getAllVersionsByPatient(patientId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        verify(maternalHistoryService).getAllVersionsByPatientId(patientId, username);
    }

    @Test
    void getMaternalHistoryVersion_shouldReturnSpecificVersion() {
        int versionNumber = 1;
        when(maternalHistoryService.getMaternalHistoryByPatientIdAndVersion(patientId, versionNumber, username))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .getMaternalHistoryVersion(patientId, versionNumber, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getVersionNumber()).isEqualTo(versionNumber);
        verify(maternalHistoryService).getMaternalHistoryByPatientIdAndVersion(patientId, versionNumber, username);
    }

    @Test
    void searchMaternalHistory_shouldReturnPagedResults() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MaternalHistoryResponseDTO> page = new PageImpl<>(List.of(responseDTO));

        when(maternalHistoryService.searchMaternalHistory(
                eq(hospitalId), any(), any(), any(), any(), any(), any(), eq(pageable), eq(username)))
                .thenReturn(page);

        ResponseEntity<Page<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .searchMaternalHistory(hospitalId, null, null, null, null, null, null, pageable, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        verify(maternalHistoryService).searchMaternalHistory(
                hospitalId, null, null, null, null, null, null, pageable, username);
    }

    @Test
    void searchMaternalHistory_shouldPassAllFilters() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MaternalHistoryResponseDTO> page = new PageImpl<>(List.of(responseDTO));
        String riskCategory = "HIGH";
        Boolean dataComplete = true;
        Boolean reviewedByProvider = false;
        LocalDateTime dateFrom = LocalDateTime.now().minusDays(30);
        LocalDateTime dateTo = LocalDateTime.now();

        when(maternalHistoryService.searchMaternalHistory(
                eq(hospitalId), eq(patientId), eq(riskCategory), eq(dataComplete), 
                eq(reviewedByProvider), eq(dateFrom), eq(dateTo), eq(pageable), eq(username)))
                .thenReturn(page);

        ResponseEntity<Page<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .searchMaternalHistory(hospitalId, patientId, riskCategory, dataComplete, 
                        reviewedByProvider, dateFrom, dateTo, pageable, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(maternalHistoryService).searchMaternalHistory(
                hospitalId, patientId, riskCategory, dataComplete, reviewedByProvider, 
                dateFrom, dateTo, pageable, username);
    }

    @Test
    void getHighRiskMaternities_shouldReturnHighRiskCases() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MaternalHistoryResponseDTO> page = new PageImpl<>(List.of(responseDTO));

        when(maternalHistoryService.getHighRiskMaternalHistory(hospitalId, pageable, username))
                .thenReturn(page);

        ResponseEntity<Page<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .getHighRiskMaternities(hospitalId, pageable, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        verify(maternalHistoryService).getHighRiskMaternalHistory(hospitalId, pageable, username);
    }

    @Test
    void getPendingReview_shouldReturnUnreviewedCases() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MaternalHistoryResponseDTO> page = new PageImpl<>(List.of(responseDTO));

        when(maternalHistoryService.getPendingReview(hospitalId, pageable, username))
                .thenReturn(page);

        ResponseEntity<Page<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .getPendingReview(hospitalId, pageable, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        verify(maternalHistoryService).getPendingReview(hospitalId, pageable, username);
    }

    @Test
    void getRequiringSpecialistReferral_shouldReturnReferralCases() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MaternalHistoryResponseDTO> page = new PageImpl<>(List.of(responseDTO));

        when(maternalHistoryService.getRequiringSpecialistReferral(hospitalId, pageable, username))
                .thenReturn(page);

        ResponseEntity<Page<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .getRequiringSpecialistReferral(hospitalId, pageable, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        verify(maternalHistoryService).getRequiringSpecialistReferral(hospitalId, pageable, username);
    }

    @Test
    void getWithPsychosocialConcerns_shouldReturnConcernCases() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<MaternalHistoryResponseDTO> page = new PageImpl<>(List.of(responseDTO));

        when(maternalHistoryService.getWithPsychosocialConcerns(hospitalId, pageable, username))
                .thenReturn(page);

        ResponseEntity<Page<MaternalHistoryResponseDTO>> response = maternalHistoryController
                .getWithPsychosocialConcerns(hospitalId, pageable, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        verify(maternalHistoryService).getWithPsychosocialConcerns(hospitalId, pageable, username);
    }

    @Test
    void deleteMaternalHistory_shouldReturnNoContentStatus() {
        ResponseEntity<Void> response = maternalHistoryController
                .deleteMaternalHistory(historyId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(maternalHistoryService).deleteMaternalHistory(historyId, username);
    }

    @Test
    void markAsReviewed_shouldUpdateReviewStatus() {
        when(maternalHistoryService.markAsReviewed(historyId, username))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .markAsReviewed(historyId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        verify(maternalHistoryService).markAsReviewed(historyId, username);
    }

    @Test
    void calculateRiskScore_shouldRecalculateRisk() {
        when(maternalHistoryService.calculateRiskScore(historyId, username))
                .thenReturn(responseDTO);

        ResponseEntity<MaternalHistoryResponseDTO> response = maternalHistoryController
                .calculateRiskScore(historyId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        verify(maternalHistoryService).calculateRiskScore(historyId, username);
    }
}
