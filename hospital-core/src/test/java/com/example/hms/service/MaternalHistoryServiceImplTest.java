package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MaternalHistoryMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.MaternalHistoryResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.impl.MaternalHistoryServiceImpl;
import com.example.hms.utility.MessageUtil;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaternalHistoryServiceImplTest {

    @Mock
    private MaternalHistoryRepository maternalHistoryRepository;

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private StaffRepository staffRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MaternalHistoryMapper maternalHistoryMapper;

    @InjectMocks
    private MaternalHistoryServiceImpl maternalHistoryService;

    private UUID patientId;
    private UUID hospitalId;
    private UUID userId;
    private UUID staffId;
    private String username;
    private Patient patient;
    private Hospital hospital;
    private User user;
    private Staff staff;
    private MaternalHistory maternalHistory;
    private MaternalHistoryRequestDTO requestDTO;
    private MaternalHistoryResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        MessageUtil.setMessageSource(null);
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("maternalHistory.notFoundById", Locale.ENGLISH, "Maternal history not found with ID: {0}");
        messageSource.addMessage("maternalHistory.versionNotFound", Locale.ENGLISH, "Maternal history version {0} not found for patient ID: {1}");
        messageSource.addMessage("maternalHistory.noneForPatient", Locale.ENGLISH, "No maternal history found for patient ID: {0}");
        messageSource.addMessage("patient.notFoundWithId", Locale.ENGLISH, "Patient not found with ID: {0}");
        messageSource.addMessage("hospital.notFoundWithId", Locale.ENGLISH, "Hospital not found with ID: {0}");
        messageSource.addMessage("user.notFoundByUsername", Locale.ENGLISH, "User not found with username: {0}");
        messageSource.addMessage("staff.notFoundForUserHospital", Locale.ENGLISH, "Staff member not found for user: {0} in hospital: {1}");
        MessageUtil.setMessageSource(messageSource);

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        userId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        username = "doctor1";

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");

        user = new User();
        user.setId(userId);
        user.setUsername(username);

        staff = new Staff();
        staff.setId(staffId);
        staff.setUser(user);
        staff.setHospital(hospital);

        maternalHistory = MaternalHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(staff)
                .recordedDate(LocalDateTime.now())
                .versionNumber(1)
                .gravida(2)
                .para(1)
                .dataComplete(true)
                .reviewedByProvider(false)
                .build();
        // Manually set ID for testing since BaseEntity auto-generates it
        maternalHistory.setId(UUID.randomUUID());

        requestDTO = MaternalHistoryRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .recordedByStaffId(staffId)
                .recordedDate(LocalDateTime.now())
                .dataComplete(true)
                .build();

        responseDTO = MaternalHistoryResponseDTO.builder()
                .id(maternalHistory.getId())
                .patientId(patientId)
                .hospitalId(hospitalId)
                .versionNumber(1)
                .build();
    }

    @Test
    void createMaternalHistory_shouldCreateSuccessfully() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.of(staff));
        when(maternalHistoryRepository.existsByPatient_Id(patientId)).thenReturn(false);
        when(maternalHistoryRepository.save(any(MaternalHistory.class))).thenReturn(maternalHistory);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService.createMaternalHistory(requestDTO, username);

        assertThat(result).isNotNull();
        assertThat(result.getPatientId()).isEqualTo(patientId);
        assertThat(result.getVersionNumber()).isEqualTo(1);

        ArgumentCaptor<MaternalHistory> captor = ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPatient()).isEqualTo(patient);
        assertThat(captor.getValue().getHospital()).isEqualTo(hospital);
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(1);
    }

    @Test
    void createMaternalHistory_shouldThrowExceptionWhenPatientNotFound() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.createMaternalHistory(requestDTO, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Patient not found");

        verify(maternalHistoryRepository, never()).save(any());
    }

    @Test
    void createMaternalHistory_shouldThrowExceptionWhenHospitalNotFound() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.createMaternalHistory(requestDTO, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Hospital not found");

        verify(maternalHistoryRepository, never()).save(any());
    }

    @Test
    void createMaternalHistory_shouldThrowExceptionWhenMaternalHistoryAlreadyExists() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(maternalHistoryRepository.existsByPatient_Id(patientId)).thenReturn(true);

        assertThatThrownBy(() -> maternalHistoryService.createMaternalHistory(requestDTO, username))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Maternal history already exists");

        verify(maternalHistoryRepository, never()).save(any());
    }

    @Test
    void updateMaternalHistory_shouldCreateNewVersion() {
        UUID existingId = UUID.randomUUID();
        MaternalHistory existingHistory = MaternalHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .versionNumber(1)
                .build();
        existingHistory.setId(existingId);

        MaternalHistory newVersion = MaternalHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .versionNumber(2)
                .build();
        newVersion.setId(UUID.randomUUID());

        when(maternalHistoryRepository.findById(existingId)).thenReturn(Optional.of(existingHistory));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.of(staff));
        when(maternalHistoryRepository.findMaxVersionByPatientId(patientId)).thenReturn(1);
        when(maternalHistoryRepository.save(any(MaternalHistory.class))).thenReturn(newVersion);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService.updateMaternalHistory(existingId, requestDTO, username);

        assertThat(result).isNotNull();

        ArgumentCaptor<MaternalHistory> captor = ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(2);
    }

    @Test
    void updateMaternalHistory_shouldThrowExceptionWhenNotFound() {
        UUID existingId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(existingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.updateMaternalHistory(existingId, requestDTO, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Maternal history not found");
    }

    @Test
    void updateMaternalHistory_shouldThrowExceptionWhenPatientIdChanges() {
        UUID existingId = UUID.randomUUID();
        Patient differentPatient = new Patient();
        differentPatient.setId(UUID.randomUUID());

        MaternalHistory existingHistory = MaternalHistory.builder()
                .patient(differentPatient)
                .hospital(hospital)
                .versionNumber(1)
                .build();
        existingHistory.setId(existingId);

        when(maternalHistoryRepository.findById(existingId)).thenReturn(Optional.of(existingHistory));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

        assertThatThrownBy(() -> maternalHistoryService.updateMaternalHistory(existingId, requestDTO, username))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot change patient ID");
    }

    @Test
    void getMaternalHistoryById_shouldReturnHistory() {
        UUID historyId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService.getMaternalHistoryById(historyId, username);

        assertThat(result).isNotNull().isEqualTo(responseDTO);
    }

    @Test
    void getMaternalHistoryById_shouldThrowExceptionWhenNotFound() {
        UUID historyId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.getMaternalHistoryById(historyId, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Maternal history not found");
    }

    @Test
    void getCurrentMaternalHistoryByPatientId_shouldReturnCurrentVersion() {
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService.getCurrentMaternalHistoryByPatientId(patientId, username);

        assertThat(result).isNotNull().isEqualTo(responseDTO);
    }

    @Test
    void getCurrentMaternalHistoryByPatientId_shouldThrowExceptionWhenNotFound() {
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.getCurrentMaternalHistoryByPatientId(patientId, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No maternal history found");
    }

    @Test
    void getAllVersionsByPatientId_shouldReturnAllVersions() {
        List<MaternalHistory> histories = List.of(maternalHistory);
        when(maternalHistoryRepository.findAllVersionsByPatientId(patientId)).thenReturn(histories);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        List<MaternalHistoryResponseDTO> result = maternalHistoryService.getAllVersionsByPatientId(patientId, username);

        assertThat(result).hasSize(1);
    }

    @Test
    void getMaternalHistoryByPatientIdAndVersion_shouldReturnSpecificVersion() {
    int versionNumber = 1;
        when(maternalHistoryRepository.findByPatientIdAndVersion(patientId, versionNumber))
                .thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService
                .getMaternalHistoryByPatientIdAndVersion(patientId, versionNumber, username);

        assertThat(result).isNotNull().isEqualTo(responseDTO);
    }

    @Test
    void getMaternalHistoryByDateRange_shouldReturnFilteredHistory() {
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        LocalDateTime endDate = LocalDateTime.now();
        List<MaternalHistory> histories = List.of(maternalHistory);

        when(maternalHistoryRepository.findByPatientIdAndDateRange(patientId, startDate, endDate))
                .thenReturn(histories);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        List<MaternalHistoryResponseDTO> result = maternalHistoryService
                .getMaternalHistoryByDateRange(patientId, startDate, endDate, username);

        assertThat(result).hasSize(1);
    }

    @Test
    void searchMaternalHistory_shouldReturnPagedResults() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MaternalHistory> page = new PageImpl<>(List.of(maternalHistory));

        when(maternalHistoryRepository.searchMaternalHistory(
                eq(hospitalId), any(), any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(page);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        Page<MaternalHistoryResponseDTO> result = maternalHistoryService.searchMaternalHistory(
                hospitalId, null, null, null, null, null, null, pageable, username);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getHighRiskMaternalHistory_shouldReturnHighRiskCases() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MaternalHistory> page = new PageImpl<>(List.of(maternalHistory));

        when(maternalHistoryRepository.findHighRiskByHospital(hospitalId, pageable)).thenReturn(page);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        Page<MaternalHistoryResponseDTO> result = maternalHistoryService
                .getHighRiskMaternalHistory(hospitalId, pageable, username);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getPendingReview_shouldReturnUnreviewedCases() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MaternalHistory> page = new PageImpl<>(List.of(maternalHistory));

        when(maternalHistoryRepository.findPendingReviewByHospital(hospitalId, pageable)).thenReturn(page);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        Page<MaternalHistoryResponseDTO> result = maternalHistoryService
                .getPendingReview(hospitalId, pageable, username);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getRequiringSpecialistReferral_shouldReturnReferralCases() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MaternalHistory> page = new PageImpl<>(List.of(maternalHistory));

        when(maternalHistoryRepository.findRequiringSpecialistReferral(hospitalId, pageable)).thenReturn(page);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        Page<MaternalHistoryResponseDTO> result = maternalHistoryService
                .getRequiringSpecialistReferral(hospitalId, pageable, username);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getWithPsychosocialConcerns_shouldReturnConcernCases() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<MaternalHistory> page = new PageImpl<>(List.of(maternalHistory));

        when(maternalHistoryRepository.findWithPsychosocialConcerns(hospitalId, pageable)).thenReturn(page);
        when(maternalHistoryMapper.toResponseDTO(any(MaternalHistory.class))).thenReturn(responseDTO);

        Page<MaternalHistoryResponseDTO> result = maternalHistoryService
                .getWithPsychosocialConcerns(hospitalId, pageable, username);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void markAsReviewed_shouldUpdateReviewStatus() {
        UUID historyId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryRepository.save(any(MaternalHistory.class))).thenReturn(maternalHistory);
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService.markAsReviewed(historyId, username);

        assertThat(result).isNotNull();
        ArgumentCaptor<MaternalHistory> captor = ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getReviewedByProvider()).isTrue();
        assertThat(captor.getValue().getReviewTimestamp()).isNotNull();
    }

    @Test
    void deleteMaternalHistory_shouldDeleteSuccessfully() {
        UUID historyId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.of(maternalHistory));

        maternalHistoryService.deleteMaternalHistory(historyId, username);

        verify(maternalHistoryRepository).delete(maternalHistory);
    }

    @Test
    void deleteMaternalHistory_shouldThrowExceptionWhenNotFound() {
        UUID historyId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.deleteMaternalHistory(historyId, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Maternal history not found");

        verify(maternalHistoryRepository, never()).delete(any());
    }

    @Test
    void calculateRiskScore_shouldCalculateLowRisk() {
        UUID historyId = UUID.randomUUID();
        maternalHistory.setGestationalDiabetesHistory(false);
        maternalHistory.setPreeclampsiaHistory(false);

        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryRepository.save(any(MaternalHistory.class))).thenReturn(maternalHistory);
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        MaternalHistoryResponseDTO result = maternalHistoryService.calculateRiskScore(historyId, username);

        assertThat(result).isNotNull();
        ArgumentCaptor<MaternalHistory> captor = ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getRiskCategory()).isEqualTo("LOW");
    }

    @Test
    void calculateRiskScore_shouldCalculateModerateRisk() {
        UUID historyId = UUID.randomUUID();
        maternalHistory.setGestationalDiabetesHistory(true);
        maternalHistory.setPretermLaborHistory(true);
        maternalHistory.setHypertension(true);

        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryRepository.save(any(MaternalHistory.class))).thenReturn(maternalHistory);
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        maternalHistoryService.calculateRiskScore(historyId, username);

        ArgumentCaptor<MaternalHistory> captor = ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getRiskCategory()).isIn("MODERATE", "HIGH");
    }

    @Test
    void calculateRiskScore_shouldCalculateHighRisk() {
        UUID historyId = UUID.randomUUID();
        maternalHistory.setPreeclampsiaHistory(true);
        maternalHistory.setEclampsiaHistory(true);
        maternalHistory.setHellpSyndromeHistory(true);
        maternalHistory.setCardiacDisease(true);

        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.of(maternalHistory));
        when(maternalHistoryRepository.save(any(MaternalHistory.class))).thenReturn(maternalHistory);
        when(maternalHistoryMapper.toResponseDTO(maternalHistory)).thenReturn(responseDTO);

        maternalHistoryService.calculateRiskScore(historyId, username);

        ArgumentCaptor<MaternalHistory> captor = ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getRiskCategory()).isEqualTo("HIGH");
    }

    @Test
    void calculateRiskScore_shouldThrowExceptionWhenNotFound() {
        UUID historyId = UUID.randomUUID();
        when(maternalHistoryRepository.findById(historyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> maternalHistoryService.calculateRiskScore(historyId, username))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Maternal history not found");
    }
}
