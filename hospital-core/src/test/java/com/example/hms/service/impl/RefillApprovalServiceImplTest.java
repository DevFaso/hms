package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefillApprovalServiceImpl")
class RefillApprovalServiceImplTest {

    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private NotificationService notificationService;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private Authentication auth;

    @InjectMocks private RefillApprovalServiceImpl service;

    private UUID userId;
    private UUID staffId;
    private UUID refillId;
    private UUID prescriptionId;
    private Patient patient;
    private Staff prescriber;
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        refillId = UUID.randomUUID();
        prescriptionId = UUID.randomUUID();

        User patientUser = new User();
        patientUser.setUsername("alice.patient");
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setUser(patientUser);
        patient.setFirstName("Alice");
        patient.setLastName("Patient");

        prescriber = new Staff();
        prescriber.setId(staffId);

        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setMedicationName("Metformin 500mg");
        prescription.setStaff(prescriber);
        prescription.setPatient(patient);
    }

    private RefillRequest pendingRefill() {
        RefillRequest r = new RefillRequest();
        r.setId(refillId);
        r.setPatient(patient);
        r.setPrescription(prescription);
        r.setStatus(RefillStatus.REQUESTED);
        r.setPreferredPharmacy("CVS");
        r.setPatientNotes("running low");
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        return r;
    }

    private void stubStaffResolution() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(staffRepository.findByUserId(userId)).thenReturn(List.of(prescriber));
    }

    @Test
    @DisplayName("approve — flips REQUESTED to APPROVED, persists notes, notifies patient")
    void approve_happyPath() {
        stubStaffResolution();
        RefillRequest pending = pendingRefill();
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(pending));
        when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RefillDecisionRequestDTO decision = RefillDecisionRequestDTO.builder()
                .providerNotes("Approved — pick up by Friday")
                .build();

        MedicationRefillResponseDTO result = service.approve(auth, refillId, decision);

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getProviderNotes()).isEqualTo("Approved — pick up by Friday");
        verify(notificationService).createNotification(anyString(), eq("alice.patient"), eq("MEDICATION_REFILL"));
    }

    @Test
    @DisplayName("reject — flips REQUESTED to DENIED")
    void reject_happyPath() {
        stubStaffResolution();
        RefillRequest pending = pendingRefill();
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(pending));
        when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        MedicationRefillResponseDTO result = service.reject(auth, refillId,
                RefillDecisionRequestDTO.builder().providerNotes("Discontinued — see clinic").build());

        assertThat(result.getStatus()).isEqualTo("DENIED");
        verify(notificationService).createNotification(anyString(), eq("alice.patient"), eq("MEDICATION_REFILL"));
    }

    @Test
    @DisplayName("approve — rejects when refill is not REQUESTED")
    void approve_rejectsAlreadyDecided() {
        stubStaffResolution();
        RefillRequest decided = pendingRefill();
        decided.setStatus(RefillStatus.APPROVED);
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> service.approve(auth, refillId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APPROVED");
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("approve — rejects another doctor's prescription")
    void approve_rejectsForeignPrescription() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        Staff someoneElse = new Staff();
        someoneElse.setId(UUID.randomUUID());
        when(staffRepository.findByUserId(userId)).thenReturn(List.of(someoneElse));
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(pendingRefill()));

        assertThatThrownBy(() -> service.approve(auth, refillId, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("approve — 404 when refill not found")
    void approve_notFound() {
        stubStaffResolution();
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(auth, refillId, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("listForProvider — delegates to status-filtered query when status given")
    void listForProvider_filtered() {
        stubStaffResolution();
        Pageable pageable = PageRequest.of(0, 10);
        Page<RefillRequest> page = new PageImpl<>(List.of(pendingRefill()));
        when(refillRequestRepository.findByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED, pageable))
                .thenReturn(page);

        Page<MedicationRefillResponseDTO> result =
                service.listForProvider(auth, RefillStatus.REQUESTED, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("listForProvider — without status, returns all the prescriber's refills")
    void listForProvider_all() {
        stubStaffResolution();
        Pageable pageable = PageRequest.of(0, 10);
        when(refillRequestRepository.findByPrescription_Staff_Id(staffId, pageable))
                .thenReturn(new PageImpl<>(List.of(pendingRefill())));

        Page<MedicationRefillResponseDTO> result = service.listForProvider(auth, null, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("countPendingForProvider — sums REQUESTED count")
    void count_pending() {
        stubStaffResolution();
        when(refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED))
                .thenReturn(7L);

        assertThat(service.countPendingForProvider(auth)).isEqualTo(7L);
    }

    @Test
    @DisplayName("pause — defers a REQUESTED refill and relays the reason to the patient")
    void pause_happyPath() {
        stubStaffResolution();
        RefillRequest pending = pendingRefill();
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(pending));
        when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        MedicationRefillResponseDTO result = service.pause(auth, refillId,
                RefillDecisionRequestDTO.builder().providerNotes("Need an A1c before renewing").build());

        assertThat(result.getStatus()).isEqualTo("PAUSED");
        assertThat(result.getProviderNotes()).isEqualTo("Need an A1c before renewing");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService)
                .createNotification(message.capture(), eq("alice.patient"), eq("MEDICATION_REFILL"));
        assertThat(message.getValue())
                .contains("on hold")
                .contains("Need an A1c before renewing");
    }

    @Test
    @DisplayName("pause — refuses without a reason, since the patient is told about the hold")
    void pause_requiresReason() {
        assertThatThrownBy(() -> service.pause(auth, refillId,
                RefillDecisionRequestDTO.builder().providerNotes("   ").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason is required");
        verify(refillRequestRepository, never()).findById(any());
    }

    @Test
    @DisplayName("pause — refuses a null decision body")
    void pause_requiresBody() {
        assertThatThrownBy(() -> service.pause(auth, refillId, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    @DisplayName("approve — a paused refill can still be approved")
    void approve_fromPaused() {
        stubStaffResolution();
        RefillRequest paused = pendingRefill();
        paused.setStatus(RefillStatus.PAUSED);
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(paused));
        when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.approve(auth, refillId, null).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("reject — a paused refill can still be denied")
    void reject_fromPaused() {
        stubStaffResolution();
        RefillRequest paused = pendingRefill();
        paused.setStatus(RefillStatus.PAUSED);
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(paused));
        when(refillRequestRepository.save(any(RefillRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.reject(auth, refillId, null).getStatus()).isEqualTo("DENIED");
    }

    @Test
    @DisplayName("pause — refuses to re-pause, which would only re-notify the patient")
    void pause_rejectsAlreadyPaused() {
        stubStaffResolution();
        RefillRequest paused = pendingRefill();
        paused.setStatus(RefillStatus.PAUSED);
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(paused));

        assertThatThrownBy(() -> service.pause(auth, refillId,
                RefillDecisionRequestDTO.builder().providerNotes("still waiting").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PAUSED");
        verify(notificationService, never()).createNotification(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("pause — refuses a refill already decided")
    void pause_rejectsDecided() {
        stubStaffResolution();
        RefillRequest denied = pendingRefill();
        denied.setStatus(RefillStatus.DENIED);
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(denied));

        assertThatThrownBy(() -> service.pause(auth, refillId,
                RefillDecisionRequestDTO.builder().providerNotes("reconsidering").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DENIED");
    }

    @Test
    @DisplayName("pause — cannot act on another doctor's prescription")
    void pause_rejectsForeignPrescription() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        Staff someoneElse = new Staff();
        someoneElse.setId(UUID.randomUUID());
        when(staffRepository.findByUserId(userId)).thenReturn(List.of(someoneElse));
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(pendingRefill()));

        assertThatThrownBy(() -> service.pause(auth, refillId,
                RefillDecisionRequestDTO.builder().providerNotes("hold").build()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("countPendingForProvider — a paused refill is no longer pending")
    void count_pendingExcludesPaused() {
        stubStaffResolution();
        when(refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED))
                .thenReturn(2L);

        assertThat(service.countPendingForProvider(auth)).isEqualTo(2L);
        verify(refillRequestRepository, never())
                .countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.PAUSED);
    }

    @Test
    @DisplayName("resolveStaffId — fails when user has no staff record")
    void resolveStaffId_missing() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(staffRepository.findByUserId(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.countPendingForProvider(auth))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("staff record");
    }
}
