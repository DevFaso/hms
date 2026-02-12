package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabResultServiceImplWorkflowTest {

    private static final String SIGNER_FULL_NAME = "Seema Signer";

    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private LabOrderRepository labOrderRepository;
    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock
    private LabResultMapper labResultMapper;
    @Mock
    private RoleValidator roleValidator;
    @Mock
    private AuthService authService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private LabResultServiceImpl labResultService;

    private UUID hospitalId;
    private LabOrder labOrder;
    private UserRoleHospitalAssignment resultAssignment;
    private LabTestDefinition labTestDefinition;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Patty");
        patient.setLastName("Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setPhoneNumberPrimary("555-1000");
        patient.setEmail("patty@example.org");

        labOrder = new LabOrder();
        labOrder.setId(UUID.randomUUID());
        labOrder.setHospital(hospital);
        labOrder.setPatient(patient);

        labTestDefinition = new LabTestDefinition();
        labTestDefinition.setId(UUID.randomUUID());
        labTestDefinition.setName("Complete Blood Count");
        labOrder.setLabTestDefinition(labTestDefinition);

        resultAssignment = new UserRoleHospitalAssignment();
        resultAssignment.setId(UUID.randomUUID());
        resultAssignment.setHospital(hospital);
    }

    @Test
    void getLabResultByIdIncludesTrendHistory() {
        UUID labResultId = UUID.randomUUID();
        LabResult current = buildLabResult(labResultId);
        LabResult previous = buildLabResult(UUID.randomUUID());
        previous.setResultDate(current.getResultDate().minusDays(3));

        LabResultResponseDTO baseResponse = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .build();

        LabResultTrendPointDTO previousPoint = LabResultTrendPointDTO.builder()
            .labResultId(previous.getId().toString())
            .resultDate(previous.getResultDate())
            .build();

        LabResultTrendPointDTO currentPoint = LabResultTrendPointDTO.builder()
            .labResultId(current.getId().toString())
            .resultDate(current.getResultDate())
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(current));
        when(labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(
                labOrder.getPatient().getId(),
                labTestDefinition.getId())
        ).thenReturn(List.of(current, previous));
        when(labResultMapper.toResponseDTO(current)).thenReturn(baseResponse);
        when(labResultMapper.toTrendPointDTO(current)).thenReturn(currentPoint);
        when(labResultMapper.toTrendPointDTO(previous)).thenReturn(previousPoint);

        LabResultResponseDTO response = labResultService.getLabResultById(labResultId, Locale.US);

        assertThat(response.getTrendHistory()).containsExactly(previousPoint, currentPoint);
    }

    @Test
    void releaseLabResultSetsMetadataAndReturnsDto() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        User actor = new User();
        actor.setId(actorId);
        actor.setFirstName("Casey");
        actor.setLastName("Clinician");
        resultAssignment.setUser(actor);

        LabResultResponseDTO responseDTO = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .released(true)
            .releasedByFullName("Casey Clinician")
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId))
            .thenReturn(Optional.of(resultAssignment));
        when(labResultRepository.save(labResult)).thenReturn(labResult);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(responseDTO);

        LabResultResponseDTO result = labResultService.releaseLabResult(labResultId, Locale.US);

        assertThat(result).isSameAs(responseDTO);
        assertThat(labResult.isReleased()).isTrue();
        assertThat(labResult.getReleasedAt()).isNotNull();
        assertThat(labResult.getReleasedByUserId()).isEqualTo(actorId);
        assertThat(labResult.getReleasedByDisplay()).isEqualTo("Casey Clinician");
        verify(labResultRepository).save(labResult);
    }

    @Test
    void releaseLabResultSkipsUpdateWhenAlreadyReleased() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);
        labResult.setReleased(true);
        labResult.setReleasedAt(LocalDateTime.now().minusHours(2));
        labResult.setReleasedByUserId(actorId);
        labResult.setReleasedByDisplay("Existing Actor");

        LabResultResponseDTO responseDTO = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .released(true)
            .releasedByFullName("Existing Actor")
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(responseDTO);

        LabResultResponseDTO result = labResultService.releaseLabResult(labResultId, Locale.US);

        assertThat(result).isSameAs(responseDTO);
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void releaseLabResultRequiresPermission() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isHospitalAdmin(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isDoctor(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isNurse(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isMidwife(actorId, hospitalId)).thenReturn(false);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThrows(BusinessException.class, () -> labResultService.releaseLabResult(labResultId, Locale.US));
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void signLabResultRecordsSignatureAndAcknowledgement() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        User actor = new User();
        actor.setId(actorId);
        actor.setFirstName("Seema");
        actor.setLastName("Signer");
        resultAssignment.setUser(actor);

        LabResultSignatureRequestDTO request = LabResultSignatureRequestDTO.builder()
            .signature("  SignedBySeema  ")
            .notes(" Reviewed and approved ")
            .build();

        LabResultResponseDTO responseDTO = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .signedBy(SIGNER_FULL_NAME)
            .signatureValue("SignedBySeema")
            .signatureNotes("Reviewed and approved")
            .acknowledged(true)
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isDoctor(actorId, hospitalId)).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId))
            .thenReturn(Optional.of(resultAssignment));
        when(labResultRepository.save(labResult)).thenReturn(labResult);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(responseDTO);

        LabResultResponseDTO result = labResultService.signLabResult(labResultId, request, Locale.US);

        assertThat(result).isSameAs(responseDTO);
        assertThat(labResult.getSignedAt()).isNotNull();
        assertThat(labResult.getSignedByUserId()).isEqualTo(actorId);
        assertThat(labResult.getSignedByDisplay()).isEqualTo(SIGNER_FULL_NAME);
        assertThat(labResult.getSignatureValue()).isEqualTo("SignedBySeema");
        assertThat(labResult.getSignatureNotes()).isEqualTo("Reviewed and approved");
        assertThat(labResult.isAcknowledged()).isTrue();
        assertThat(labResult.getAcknowledgedByUserId()).isEqualTo(actorId);
        assertThat(labResult.getAcknowledgedByDisplay()).isEqualTo(SIGNER_FULL_NAME);
        verify(labResultRepository).save(labResult);
    }

    @Test
    void signLabResultRequiresPermission() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isDoctor(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isMidwife(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(false);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThrows(BusinessException.class,
            () -> labResultService.signLabResult(labResultId, new LabResultSignatureRequestDTO(), Locale.US));
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void releaseLabResultThrowsWhenResultMissing() {
        UUID labResultId = UUID.randomUUID();
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
            () -> labResultService.releaseLabResult(labResultId, Locale.US));
    }

    private LabResult buildLabResult(UUID labResultId) {
        LabResult labResult = new LabResult();
        labResult.setId(labResultId);
        labResult.setLabOrder(labOrder);
        labResult.setAssignment(resultAssignment);
        labResult.setResultValue("Pending");
        labResult.setResultUnit("mmol/L");
        labResult.setResultDate(LocalDateTime.now().minusDays(1));
        labResult.setNotes("Initial pending result");
        return labResult;
    }
}
