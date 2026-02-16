package com.example.hms.service;

import com.example.hms.enums.ActingMode;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LinkPatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.ActingContext;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientInsuranceServiceImplTest {

    @Mock private PatientInsuranceRepository patientInsuranceRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientInsuranceMapper patientInsuranceMapper;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private PatientInsuranceServiceImpl service;

    private UUID patientId;
    private UUID insuranceId;
    private UUID userId;
    private UUID hospitalId;
    private Patient patient;
    private User user;
    private Locale locale;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        insuranceId = UUID.randomUUID();
        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        locale = Locale.ENGLISH;

        user = new User();
        user.setId(userId);

        patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setUser(user);
    }

    @Test
    void addInsuranceToPatient_success() {
        PatientInsuranceRequestDTO dto = new PatientInsuranceRequestDTO();
        dto.setPatientId(patientId);
        PatientInsurance insurance = new PatientInsurance();
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceMapper.toPatientInsurance(dto, patient)).thenReturn(insurance);
        when(patientInsuranceRepository.save(insurance)).thenReturn(insurance);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.addInsuranceToPatient(dto, locale);

        assertThat(result).isEqualTo(responseDTO);
        assertThat(insurance.getAssignment()).isNull();
        verify(patientInsuranceRepository).save(insurance);
    }

    @Test
    void addInsuranceToPatient_nullPatientId_throwsBusinessException() {
        PatientInsuranceRequestDTO dto = new PatientInsuranceRequestDTO();
        dto.setPatientId(null);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("Patient required");

        assertThatThrownBy(() -> service.addInsuranceToPatient(dto, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void getPatientInsuranceById_success() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        insurance.setPatient(patient);
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.getPatientInsuranceById(insuranceId, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void getPatientInsuranceById_notFound_throws() {
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getPatientInsuranceById(insuranceId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getInsurancesByPatientId_success() {
        PatientInsurance ins1 = new PatientInsurance();
        PatientInsuranceResponseDTO dto1 = new PatientInsuranceResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of(ins1));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(ins1)).thenReturn(dto1);

        List<PatientInsuranceResponseDTO> result = service.getInsurancesByPatientId(patientId, locale);
        assertThat(result).hasSize(1).contains(dto1);
    }

    @Test
    void updatePatientInsurance_success() {
        PatientInsuranceRequestDTO dto = new PatientInsuranceRequestDTO();
        dto.setPatientId(patientId);
        PatientInsurance existing = new PatientInsurance();
        existing.setId(insuranceId);
        existing.setPatient(patient);
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(existing));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.save(existing)).thenReturn(existing);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(existing)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.updatePatientInsurance(insuranceId, dto, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void deletePatientInsurance_success() {
        PatientInsurance existing = new PatientInsurance();
        existing.setId(insuranceId);
        existing.setPatient(patient);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(existing));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);

        service.deletePatientInsurance(insuranceId, locale);
        verify(patientInsuranceRepository).deleteById(insuranceId);
    }

    @Test
    void linkPatientInsurance_patientMode_success() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).build();
        ActingContext ctx = new ActingContext(userId, null, ActingMode.PATIENT, null);
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(patientInsuranceRepository.save(insurance)).thenReturn(insurance);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.linkPatientInsurance(insuranceId, req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void linkPatientInsurance_patientMode_wrongUser_throwsAccessDenied() {
        UUID otherUserId = UUID.randomUUID();
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).build();
        ActingContext ctx = new ActingContext(otherUserId, null, ActingMode.PATIENT, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("denied");

        assertThatThrownBy(() -> service.linkPatientInsurance(insuranceId, req, ctx, locale))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void linkPatientInsurance_patientMode_withHospitalId_throwsBusiness() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();
        ActingContext ctx = new ActingContext(userId, null, ActingMode.PATIENT, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("cannot link");

        assertThatThrownBy(() -> service.linkPatientInsurance(insuranceId, req, ctx, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkPatientInsurance_staffMode_success() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(patientInsuranceRepository.save(insurance)).thenReturn(insurance);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.linkPatientInsurance(insuranceId, req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
        assertThat(insurance.getAssignment()).isEqualTo(assignment);
    }

    @Test
    void linkPatientInsurance_staffMode_noHospital_throwsBusiness() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).build();
        ActingContext ctx = new ActingContext(userId, null, ActingMode.STAFF, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("hospital required");

        assertThatThrownBy(() -> service.linkPatientInsurance(insuranceId, req, ctx, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkPatientInsurance_staffMode_noPermission_throwsAccessDenied() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.isHospitalAdminFromAuthGlobalOnly()).thenReturn(false);
        when(roleValidator.canLinkInsurance(userId, hospitalId)).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("forbidden");

        assertThatThrownBy(() -> service.linkPatientInsurance(insuranceId, req, ctx, locale))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void upsertAndLinkByInsuranceId_success() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(patientInsuranceRepository.save(insurance)).thenReturn(insurance);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.upsertAndLinkByInsuranceId(insuranceId, req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void upsertAndLinkByInsuranceId_nullPatientId_throwsBusiness() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder().build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("Patient required");

        assertThatThrownBy(() -> service.upsertAndLinkByInsuranceId(insuranceId, req, ctx, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void upsertAndLinkByNaturalKey_createsNew_success() {
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId)
            .payerCode("AETNA").policyNumber("POL123").build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        PatientInsuranceResponseDTO responseDTO = new PatientInsuranceResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(patientInsuranceRepository.findByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(patientId, "AETNA", "POL123"))
            .thenReturn(Optional.empty());
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.upsertAndLinkByNaturalKey(req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void upsertAndLinkByNaturalKey_missingPayerCode_throwsBusiness() {
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).policyNumber("POL123").build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("payer required");

        assertThatThrownBy(() -> service.upsertAndLinkByNaturalKey(req, ctx, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void upsertAndLinkByNaturalKey_patientMode_withHospital_throwsBusiness() {
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId)
            .payerCode("AETNA").policyNumber("POL123").build();
        ActingContext ctx = new ActingContext(userId, null, ActingMode.PATIENT, null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("cannot link");

        assertThatThrownBy(() -> service.upsertAndLinkByNaturalKey(req, ctx, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void enforceSelfAccessIfPatient_wrongUser_throwsAccessDenied() {
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        Patient otherPatient = Patient.builder().build();
        otherPatient.setId(UUID.randomUUID());
        otherPatient.setUser(otherUser);

        PatientInsuranceRequestDTO dto = new PatientInsuranceRequestDTO();
        dto.setPatientId(otherPatient.getId());

        when(patientRepository.findById(otherPatient.getId())).thenReturn(Optional.of(otherPatient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(true);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("denied");

        assertThatThrownBy(() -> service.addInsuranceToPatient(dto, locale))
            .isInstanceOf(AccessDeniedException.class);
    }
}
