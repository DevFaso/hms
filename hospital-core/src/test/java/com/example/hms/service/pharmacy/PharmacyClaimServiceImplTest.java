package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacyClaimMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-52: unit tests for pharmacy claim service — lifecycle transitions,
 * tenant isolation, and validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyClaimServiceImpl")
class PharmacyClaimServiceImplTest {

    @Mock private PharmacyClaimRepository claimRepository;
    @Mock private DispenseRepository dispenseRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private PharmacyClaimMapper claimMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;

    @InjectMocks private PharmacyClaimServiceImpl service;

    private UUID hospitalId;
    private UUID dispenseId;
    private UUID patientId;
    private UUID userId;
    private Hospital hospital;
    private Patient patient;
    private Dispense dispense;
    private User user;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        dispenseId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        userId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        Pharmacy pharmacy = new Pharmacy();
        pharmacy.setHospital(hospital);

        dispense = new Dispense();
        dispense.setId(dispenseId);
        dispense.setPatient(patient);
        dispense.setPharmacy(pharmacy);

        user = new User();
        user.setId(userId);
    }

    private PharmacyClaimRequestDTO request() {
        return PharmacyClaimRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .build();
    }

    @Test
    @DisplayName("createClaim: persists DRAFT and audits")
    void createDraftSucceeds() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        PharmacyClaim entity = PharmacyClaim.builder()
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .claimStatus(PharmacyClaimStatus.DRAFT)
                .hospital(hospital)
                .build();
        when(claimMapper.toEntity(any(), any(), any(), any())).thenReturn(entity);
        when(claimRepository.save(any())).thenAnswer(inv -> {
            PharmacyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        PharmacyClaimResponseDTO result = service.createClaim(request());

        assertThat(result).isNotNull();
        verify(claimRepository).save(any());
        verify(support).logAudit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("createClaim: rejects cross-hospital dispense")
    void rejectsCrossHospitalDispense() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        dispense.getPharmacy().setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects non-positive amount")
    void rejectsNonPositiveAmount() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyClaimRequestDTO dto = request();
        dto.setAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("submitClaim: transitions DRAFT -> SUBMITTED, sets timestamps and user")
    void submitFromDraft() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.submitClaim(UUID.randomUUID());

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.SUBMITTED);
        assertThat(existing.getSubmittedAt()).isNotNull();
        assertThat(existing.getSubmittedByUser()).isEqualTo(user);
    }

    @Test
    @DisplayName("submitClaim: rejects non-DRAFT source status")
    void submitRejectsWrongSourceStatus() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submitClaim(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("markAccepted: only from SUBMITTED")
    void acceptOnlyFromSubmitted() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markAccepted(UUID.randomUUID(), "ok");

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);
        assertThat(existing.getNotes()).isEqualTo("ok");
    }

    @Test
    @DisplayName("markRejected: requires a reason")
    void rejectRequiresReason() {
        assertThatThrownBy(() -> service.markRejected(UUID.randomUUID(), " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("markPaid: only from ACCEPTED")
    void payOnlyFromAccepted() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.ACCEPTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markPaid(UUID.randomUUID(), null);

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.PAID);
    }

    @Test
    @DisplayName("markPaid: rejects transition from SUBMITTED (must be ACCEPTED first)")
    void payRejectsFromSubmitted() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.markPaid(UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("loadInScope: rejects cross-hospital reads (tenant isolation)")
    void getClaimRejectsOtherHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        PharmacyClaim existing = draftClaim();
        existing.setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.getClaim(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private PharmacyClaim draftClaim() {
        PharmacyClaim c = PharmacyClaim.builder()
                .dispense(dispense)
                .patient(patient)
                .hospital(hospital)
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .claimStatus(PharmacyClaimStatus.DRAFT)
                .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    @Test
    @DisplayName("createClaim: rejects null DTO")
    void rejectsNullDto() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createClaim(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("createClaim: rejects hospital mismatch")
    void rejectsHospitalMismatch() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyClaimRequestDTO dto = request();
        dto.setHospitalId(UUID.randomUUID());

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hospital");
    }

    @Test
    @DisplayName("createClaim: rejects dispense not found")
    void rejectsDispenseNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects dispense whose patient does not match")
    void rejectsPatientMismatchOnCreate() {
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        dispense.setPatient(other);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Patient");
    }

    @Test
    @DisplayName("createClaim: rejects dispense with null patient")
    void rejectsNullPatient() {
        dispense.setPatient(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("createClaim: rejects dispense with null pharmacy")
    void rejectsNullPharmacy() {
        dispense.setPharmacy(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects when patient record is missing")
    void rejectsPatientRepoMissing() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects when hospital record is missing")
    void rejectsHospitalRepoMissing() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createClaim(request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: defaults claim status to DRAFT when mapper returns null status")
    void defaultsStatusToDraft() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        PharmacyClaim entity = PharmacyClaim.builder()
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .hospital(hospital)
                .claimStatus(null)
                .build();
        when(claimMapper.toEntity(any(), any(), any(), any())).thenReturn(entity);
        when(claimRepository.save(any())).thenAnswer(inv -> {
            PharmacyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.createClaim(request());

        verify(claimRepository).save(org.mockito.ArgumentMatchers.argThat(
                c -> c.getClaimStatus() == PharmacyClaimStatus.DRAFT));
    }

    @Test
    @DisplayName("submitClaim: rejects when current user id is null")
    void submitRejectsWhenCurrentUserIdNull() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(roleValidator.getCurrentUserId()).thenReturn(null);

        assertThatThrownBy(() -> service.submitClaim(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    @DisplayName("submitClaim: rejects when user record is missing")
    void submitRejectsWhenUserMissing() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitClaim(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("markRejected: transitions SUBMITTED -> REJECTED with reason")
    void rejectSucceedsWithReason() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markRejected(UUID.randomUUID(), "missing coverage");

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.REJECTED);
        assertThat(existing.getRejectionReason()).isEqualTo("missing coverage");
    }

    @Test
    @DisplayName("markRejected: rejects null reason")
    void rejectRequiresNonNullReason() {
        assertThatThrownBy(() -> service.markRejected(UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("markAccepted: null notes leaves original notes untouched")
    void acceptWithNullNotesDoesNotUpdateNotes() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        existing.setNotes("original");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markAccepted(UUID.randomUUID(), null);

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);
        assertThat(existing.getNotes()).isEqualTo("original");
    }

    @Test
    @DisplayName("markAccepted: blank notes leaves original notes untouched")
    void acceptWithBlankNotes() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        existing.setNotes("original");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markAccepted(UUID.randomUUID(), "   ");

        assertThat(existing.getNotes()).isEqualTo("original");
    }

    @Test
    @DisplayName("markAccepted: rejects transition from DRAFT")
    void acceptRejectsFromDraft() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.markAccepted(UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("loadInScope: rejects claim with null hospital")
    void loadRejectsNullHospital() {
        PharmacyClaim existing = draftClaim();
        existing.setHospital(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.getClaim(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("loadInScope: rejects when claim is not found")
    void loadRejectsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClaim(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getClaim: happy path returns response DTO")
    void getClaimHappyPath() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimMapper.toResponseDTO(existing))
                .thenReturn(PharmacyClaimResponseDTO.builder().build());

        assertThat(service.getClaim(UUID.randomUUID())).isNotNull();
    }

    @Test
    @DisplayName("listByHospital: delegates with active hospital id")
    void listByHospitalDelegates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalId(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByHospital(org.springframework.data.domain.PageRequest.of(0, 10)))
                .isNotNull();
    }

    @Test
    @DisplayName("listByDispense: delegates to repository")
    void listByDispenseDelegates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByDispenseIdAndHospitalId(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByDispense(UUID.randomUUID(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isNotNull();
    }

    @Test
    @DisplayName("listByPatient: delegates to repository")
    void listByPatientDelegates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByPatientIdAndHospitalId(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByPatient(UUID.randomUUID(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isNotNull();
    }
}
