package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.BirthPlanMapper;
import com.example.hms.model.BirthPlan;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.payload.dto.clinical.BirthPlanProviderReviewRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanResponseDTO;
import com.example.hms.repository.BirthPlanRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BirthPlanServiceImplTest {

    @Mock
    private BirthPlanRepository birthPlanRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BirthPlanMapper birthPlanMapper;

    @InjectMocks
    private BirthPlanServiceImpl birthPlanService;

    private User patientUser;
    private User doctorUser;
    private User midwifeUser;
    private Patient patient;
    private Hospital hospital;
    private BirthPlan birthPlan;
    private BirthPlanRequestDTO requestDTO;
    private BirthPlanResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        // Setup Patient User
        patientUser = new User();
        patientUser.setId(UUID.randomUUID());
        patientUser.setUsername("patient@test.com");
        patientUser.setUserRoles(createUserRoles("ROLE_PATIENT"));

        // Setup Doctor User
        doctorUser = new User();
        doctorUser.setId(UUID.randomUUID());
        doctorUser.setUsername("doctor@test.com");
        doctorUser.setUserRoles(createUserRoles("ROLE_DOCTOR"));

        // Setup Midwife User
        midwifeUser = new User();
        midwifeUser.setId(UUID.randomUUID());
        midwifeUser.setUsername("midwife@test.com");
        midwifeUser.setUserRoles(createUserRoles("ROLE_MIDWIFE"));

        // Setup Patient
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setUser(patientUser);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");

        // Setup Hospital
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Test Hospital");

        // Setup Hospital Registration
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        patient.setHospitalRegistrations(Set.of(registration));

        // Setup Birth Plan
        birthPlan = new BirthPlan();
        birthPlan.setId(UUID.randomUUID());
        birthPlan.setPatient(patient);
        birthPlan.setHospital(hospital);
        birthPlan.setPatientName("Jane Doe");
        birthPlan.setExpectedDueDate(LocalDate.now().plusMonths(3));
        birthPlan.setProviderReviewRequired(true);
        birthPlan.setProviderReviewed(false);
        birthPlan.setCreatedAt(LocalDateTime.now());
        birthPlan.setUpdatedAt(LocalDateTime.now());

        // Setup Request DTO
        requestDTO = new BirthPlanRequestDTO();
        requestDTO.setPatientId(patient.getId());
        requestDTO.setHospitalId(hospital.getId());

        BirthPlanRequestDTO.IntroductionDTO intro = new BirthPlanRequestDTO.IntroductionDTO();
        intro.setPatientName("Jane Doe");
        intro.setExpectedDueDate(LocalDate.now().plusMonths(3));
        requestDTO.setIntroduction(intro);

        requestDTO.setFlexibilityAcknowledgment(true);

        // Setup Response DTO
        responseDTO = new BirthPlanResponseDTO();
        responseDTO.setId(birthPlan.getId());
        responseDTO.setPatientId(patient.getId());
        responseDTO.setHospitalId(hospital.getId());
    }

    private Set<UserRole> createUserRoles(String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);
        role.setName(roleCode.replace("ROLE_", ""));

        UserRole userRole = new UserRole();
        userRole.setRole(role);

        return Set.of(userRole);
    }

    @Test
    void createBirthPlan_asPatient_success() {
        // Given
        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));
        when(patientRepository.findByUserId(patientUser.getId()))
            .thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospital.getId()))
            .thenReturn(Optional.of(hospital));
        when(birthPlanRepository.save(any(BirthPlan.class)))
            .thenReturn(birthPlan);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        BirthPlanResponseDTO result = birthPlanService.createBirthPlan(requestDTO, patientUser.getUsername());

        // Then
        assertNotNull(result);
        assertEquals(responseDTO.getId(), result.getId());

        ArgumentCaptor<BirthPlan> birthPlanCaptor = ArgumentCaptor.forClass(BirthPlan.class);
        verify(birthPlanRepository).save(birthPlanCaptor.capture());

        BirthPlan savedPlan = birthPlanCaptor.getValue();
        assertEquals(patient, savedPlan.getPatient());
        assertNotNull(savedPlan.getCreatedAt());
        assertTrue(savedPlan.isProviderReviewRequired());
    }

    @Test
    void createBirthPlan_asDoctor_success() {
        // Given
        when(userRepository.findByUsername(doctorUser.getUsername()))
            .thenReturn(Optional.of(doctorUser));
        when(patientRepository.findById(patient.getId()))
            .thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospital.getId()))
            .thenReturn(Optional.of(hospital));
        when(birthPlanRepository.save(any(BirthPlan.class)))
            .thenReturn(birthPlan);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        BirthPlanResponseDTO result = birthPlanService.createBirthPlan(requestDTO, doctorUser.getUsername());

        // Then
        assertNotNull(result);
        verify(patientRepository).findById(patient.getId());
        verify(hospitalRepository).findById(hospital.getId());
    }

    @Test
    void createBirthPlan_userNotFound_throwsException() {
        // Given
        when(userRepository.findByUsername(anyString()))
            .thenReturn(Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () ->
            birthPlanService.createBirthPlan(requestDTO, "nonexistent@test.com")
        );
    }

    @Test
    void updateBirthPlan_asPatient_success() {
        // Given
        UUID planId = birthPlan.getId();
        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));
        when(birthPlanRepository.findById(planId))
            .thenReturn(Optional.of(birthPlan));
        when(patientRepository.findByUserId(patientUser.getId()))
            .thenReturn(Optional.of(patient));
        when(birthPlanRepository.save(any(BirthPlan.class)))
            .thenReturn(birthPlan);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        BirthPlanResponseDTO result = birthPlanService.updateBirthPlan(planId, requestDTO, patientUser.getUsername());

        // Then
        assertNotNull(result);
        verify(birthPlanMapper).updateEntityFromRequest(eq(birthPlan), eq(requestDTO));
        verify(birthPlanRepository).save(birthPlan);
    }

    @Test
    void updateBirthPlan_resetsProviderReview_whenPreviouslyReviewed() {
        // Given
        UUID planId = birthPlan.getId();
        birthPlan.setProviderReviewed(true);
        birthPlan.setProviderSignature("Dr. Smith");

        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));
        when(birthPlanRepository.findById(planId))
            .thenReturn(Optional.of(birthPlan));
        when(patientRepository.findByUserId(patientUser.getId()))
            .thenReturn(Optional.of(patient));
        when(birthPlanRepository.save(any(BirthPlan.class)))
            .thenReturn(birthPlan);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        birthPlanService.updateBirthPlan(planId, requestDTO, patientUser.getUsername());

        // Then
        ArgumentCaptor<BirthPlan> captor = ArgumentCaptor.forClass(BirthPlan.class);
        verify(birthPlanRepository).save(captor.capture());

        BirthPlan savedPlan = captor.getValue();
        assertFalse(savedPlan.isProviderReviewed());
        assertNull(savedPlan.getProviderSignature());
        assertNull(savedPlan.getProviderSignatureDate());
        assertNull(savedPlan.getProviderComments());
    }

    @Test
    void getBirthPlanById_asPatient_ownPlan_success() {
        // Given
        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));
        when(birthPlanRepository.findById(birthPlan.getId()))
            .thenReturn(Optional.of(birthPlan));
        when(patientRepository.findByUserId(patientUser.getId()))
            .thenReturn(Optional.of(patient));
        when(birthPlanMapper.toResponseDTO(birthPlan))
            .thenReturn(responseDTO);

        // When
        BirthPlanResponseDTO result = birthPlanService.getBirthPlanById(birthPlan.getId(), patientUser.getUsername());

        // Then
        assertNotNull(result);
        assertEquals(responseDTO.getId(), result.getId());
    }

    @Test
    void getBirthPlanById_asPatient_othersPlan_throwsAccessDenied() {
        // Given
        Patient otherPatient = new Patient();
        otherPatient.setId(UUID.randomUUID());
        birthPlan.setPatient(otherPatient);

        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));
        when(birthPlanRepository.findById(birthPlan.getId()))
            .thenReturn(Optional.of(birthPlan));
        when(patientRepository.findByUserId(patientUser.getId()))
            .thenReturn(Optional.of(patient));

        // When & Then
        assertThrows(AccessDeniedException.class, () ->
            birthPlanService.getBirthPlanById(birthPlan.getId(), patientUser.getUsername())
        );
    }

    @Test
    void getBirthPlansByPatientId_asDoctor_success() {
        // Given
        List<BirthPlan> plans = Arrays.asList(birthPlan);
        when(userRepository.findByUsername(doctorUser.getUsername()))
            .thenReturn(Optional.of(doctorUser));
        when(patientRepository.findById(patient.getId()))
            .thenReturn(Optional.of(patient));
        when(birthPlanRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId()))
            .thenReturn(plans);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        List<BirthPlanResponseDTO> result = birthPlanService.getBirthPlansByPatientId(
            patient.getId(), doctorUser.getUsername()
        );

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void getActiveBirthPlan_returnsLatest() {
        // Given
        when(userRepository.findByUsername(doctorUser.getUsername()))
            .thenReturn(Optional.of(doctorUser));
        when(patientRepository.findById(patient.getId()))
            .thenReturn(Optional.of(patient));
        when(birthPlanRepository.findActiveBirthPlanByPatientId(patient.getId()))
            .thenReturn(Optional.of(birthPlan));
        when(birthPlanMapper.toResponseDTO(birthPlan))
            .thenReturn(responseDTO);

        // When
        BirthPlanResponseDTO result = birthPlanService.getActiveBirthPlan(
            patient.getId(), doctorUser.getUsername()
        );

        // Then
        assertNotNull(result);
        assertEquals(responseDTO.getId(), result.getId());
    }

    @Test
    void getActiveBirthPlan_noPlanExists_returnsNull() {
        // Given
        when(userRepository.findByUsername(doctorUser.getUsername()))
            .thenReturn(Optional.of(doctorUser));
        when(patientRepository.findById(patient.getId()))
            .thenReturn(Optional.of(patient));
        when(birthPlanRepository.findActiveBirthPlanByPatientId(patient.getId()))
            .thenReturn(Optional.empty());

        // When
        BirthPlanResponseDTO result = birthPlanService.getActiveBirthPlan(
            patient.getId(), doctorUser.getUsername()
        );

        // Then
        assertNull(result);
    }

    @Test
    void searchBirthPlans_asDoctor_success() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        Page<BirthPlan> planPage = new PageImpl<>(Arrays.asList(birthPlan));

        when(userRepository.findByUsername(doctorUser.getUsername()))
            .thenReturn(Optional.of(doctorUser));
        when(birthPlanRepository.searchBirthPlans(
            eq(hospital.getId()), any(), any(), any(), any(), eq(pageable)
        )).thenReturn(planPage);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        Page<BirthPlanResponseDTO> result = birthPlanService.searchBirthPlans(
            hospital.getId(), null, null, null, null, pageable, doctorUser.getUsername()
        );

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void searchBirthPlans_asPatient_throwsAccessDenied() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));

        // When & Then
        assertThrows(AccessDeniedException.class, () ->
            birthPlanService.searchBirthPlans(
                hospital.getId(), null, null, null, null, pageable, patientUser.getUsername()
            )
        );
    }

    @Test
    void providerReview_asMidwife_success() {
        // Given
        BirthPlanProviderReviewRequestDTO reviewDTO = new BirthPlanProviderReviewRequestDTO();
        reviewDTO.setReviewed(true);
        reviewDTO.setSignature("Midwife Jane Smith");
        reviewDTO.setComments("Reviewed and approved");

        when(userRepository.findByUsername(midwifeUser.getUsername()))
            .thenReturn(Optional.of(midwifeUser));
        when(birthPlanRepository.findById(birthPlan.getId()))
            .thenReturn(Optional.of(birthPlan));
        when(birthPlanRepository.save(any(BirthPlan.class)))
            .thenReturn(birthPlan);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        BirthPlanResponseDTO result = birthPlanService.providerReview(
            birthPlan.getId(), reviewDTO, midwifeUser.getUsername()
        );

        // Then
        assertNotNull(result);
        ArgumentCaptor<BirthPlan> captor = ArgumentCaptor.forClass(BirthPlan.class);
        verify(birthPlanRepository).save(captor.capture());

        BirthPlan reviewedPlan = captor.getValue();
        assertTrue(reviewedPlan.isProviderReviewed());
        assertEquals("Midwife Jane Smith", reviewedPlan.getProviderSignature());
        assertNotNull(reviewedPlan.getProviderSignatureDate());
        assertEquals("Reviewed and approved", reviewedPlan.getProviderComments());
    }

    @Test
    void providerReview_asNurse_throwsAccessDenied() {
        // Given
        User nurseUser = new User();
        nurseUser.setUsername("nurse@test.com");
        nurseUser.setUserRoles(createUserRoles("ROLE_NURSE"));

        BirthPlanProviderReviewRequestDTO reviewDTO = new BirthPlanProviderReviewRequestDTO();
        reviewDTO.setReviewed(true);
        reviewDTO.setSignature("Nurse Smith");

        when(userRepository.findByUsername(nurseUser.getUsername()))
            .thenReturn(Optional.of(nurseUser));
        when(birthPlanRepository.findById(birthPlan.getId()))
            .thenReturn(Optional.of(birthPlan));

        // When & Then
        assertThrows(AccessDeniedException.class, () ->
            birthPlanService.providerReview(birthPlan.getId(), reviewDTO, nurseUser.getUsername())
        );
    }

    @Test
    void deleteBirthPlan_asPatient_ownPlan_success() {
        // Given
        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));
        when(birthPlanRepository.findById(birthPlan.getId()))
            .thenReturn(Optional.of(birthPlan));
        when(patientRepository.findByUserId(patientUser.getId()))
            .thenReturn(Optional.of(patient));

        // When
        birthPlanService.deleteBirthPlan(birthPlan.getId(), patientUser.getUsername());

        // Then
        verify(birthPlanRepository).delete(birthPlan);
    }

    @Test
    void getPendingReviews_asDoctor_success() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        Page<BirthPlan> planPage = new PageImpl<>(Arrays.asList(birthPlan));

        when(userRepository.findByUsername(doctorUser.getUsername()))
            .thenReturn(Optional.of(doctorUser));
        when(birthPlanRepository.findPendingReviewByHospital(hospital.getId(), pageable))
            .thenReturn(planPage);
        when(birthPlanMapper.toResponseDTO(any(BirthPlan.class)))
            .thenReturn(responseDTO);

        // When
        Page<BirthPlanResponseDTO> result = birthPlanService.getPendingReviews(
            hospital.getId(), pageable, doctorUser.getUsername()
        );

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        verify(birthPlanRepository).findPendingReviewByHospital(hospital.getId(), pageable);
    }

    @Test
    void getPendingReviews_asPatient_throwsAccessDenied() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByUsername(patientUser.getUsername()))
            .thenReturn(Optional.of(patientUser));

        // When & Then
        assertThrows(AccessDeniedException.class, () ->
            birthPlanService.getPendingReviews(hospital.getId(), pageable, patientUser.getUsername())
        );
    }
}
