package com.example.hms.service;

import static org.mockito.ArgumentMatchers.eq;
import com.example.hms.service.support.PatientChartAccess;
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
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private PatientSubjectReadGuard subjectReadGuard;
    /** A staff caller's scope: these cases resolve the hospital through the adapter. */
    @Mock private com.example.hms.security.tenant.ActingScopeResolver actingScopeResolver;
    @Mock private com.example.hms.service.billing.PayerCatalogueService payerCatalogueService;

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

        assertThatThrownBy(() -> service.getPatientInsuranceById(insuranceId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getInsurancesByPatientId_success() {
        PatientInsurance ins1 = new PatientInsurance();
        PatientInsuranceResponseDTO dto1 = new PatientInsuranceResponseDTO();

        // The read authorizes through PatientChartAccess now: unscoped lookup plus a
        // registration check, instead of the tenant-scoped findById that filtered on
        // Patient.hospitalId — the patient's FIRST hospital — and so 404'd insurance
        // for every multi-hospital patient viewed from their second hospital.
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of(ins1));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(ins1)).thenReturn(dto1);
        // Review of #1202: a list maps in one call (its plans' names read once).
        when(patientInsuranceMapper.toPatientInsuranceResponseDTOs(org.mockito.ArgumentMatchers.anyList()))
            .thenAnswer(call -> ((List<PatientInsurance>) call.getArgument(0)).stream()
                .map(patientInsuranceMapper::toPatientInsuranceResponseDTO).toList());

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
        when(subjectReadGuard.callerOwns(patient)).thenReturn(true);
        when(patientInsuranceRepository.save(insurance)).thenReturn(insurance);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(insurance)).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.linkPatientInsurance(insuranceId, req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
        assertThat(insurance.getPatient()).isSameAs(patient);
    }

    @Test
    void linkPatientInsurance_patientMode_wrongUser_answersAsAMissingPatient() {
        UUID otherUserId = UUID.randomUUID();
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).build();
        ActingContext ctx = new ActingContext(otherUserId, null, ActingMode.PATIENT, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(subjectReadGuard.callerOwns(patient)).thenReturn(false);

        String refused = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.linkPatientInsurance(insuranceId, req, ctx, locale)).getMessage();
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        String missing = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.linkPatientInsurance(insuranceId, req, ctx, locale)).getMessage();
        assertThat(refused).isEqualTo(missing);
        assertThat(insurance.getPatient()).isNull();
        verify(patientInsuranceRepository, never()).save(any());
    }

    @Test
    void linkPatientInsurance_patientMode_anotherPatientsInsurance_answersAsAMissingInsurance() {
        Patient otherPatient = Patient.builder().build();
        otherPatient.setId(UUID.randomUUID());
        PatientInsurance foreign = new PatientInsurance();
        foreign.setId(insuranceId);
        foreign.setPatient(otherPatient);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).build();
        ActingContext ctx = new ActingContext(userId, null, ActingMode.PATIENT, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(foreign));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(subjectReadGuard.callerOwns(patient)).thenReturn(true);
        when(subjectReadGuard.callerOwns(otherPatient)).thenReturn(false);

        String refused = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.linkPatientInsurance(insuranceId, req, ctx, locale)).getMessage();
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());
        String missing = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.linkPatientInsurance(insuranceId, req, ctx, locale)).getMessage();
        assertThat(refused).isEqualTo(missing);
        // Another patient's coverage is not re-pointed at the caller.
        assertThat(foreign.getPatient()).isSameAs(otherPatient);
        verify(patientInsuranceRepository, never()).save(any());
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
        when(subjectReadGuard.callerOwns(patient)).thenReturn(true);

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
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(call -> call.getArgument(0));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.linkPatientInsurance(insuranceId, req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
        // Review of #1202: the shared (untagged) row is never re-tagged; this hospital gets its own copy.
        assertThat(insurance.getAssignment()).isNull();
        org.mockito.ArgumentCaptor<PatientInsurance> saved = org.mockito.ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository).save(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(insurance);
        assertThat(saved.getValue().getAssignment()).isEqualTo(assignment);
        assertThat(saved.getValue().getPatient()).isEqualTo(patient);
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
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(call -> call.getArgument(0));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.upsertAndLinkByInsuranceId(insuranceId, req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
        assertThat(insurance.getAssignment()).as("the shared row stays shared").isNull();
    }

    @Test
    void upsertAndLinkByInsuranceId_nullPatientId_throwsBusiness() {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(insuranceId);
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder().build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insurance));

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
        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, hospitalId, "AETNA", "POL123"))
            .thenReturn(Optional.empty());
        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignmentIsNullAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, "AETNA", "POL123"))
            .thenReturn(Optional.empty());
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(responseDTO);

        PatientInsuranceResponseDTO result = service.upsertAndLinkByNaturalKey(req, ctx, locale);
        assertThat(result).isEqualTo(responseDTO);
        // QA D925: a new policy with no name typed is named after its payer.
        org.mockito.ArgumentCaptor<PatientInsurance> saved = org.mockito.ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getProviderName()).isEqualTo("AETNA");
    }

    @Test
    void upsertAndLinkByNaturalKey_aTypedNameWins_andAnExistingNameIsKept_D925() {
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(new UserRoleHospitalAssignment()));
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(new PatientInsuranceResponseDTO());

        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, hospitalId, "CNAMU", "AMU-1")).thenReturn(Optional.empty());
        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignmentIsNullAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, "CNAMU", "AMU-1")).thenReturn(Optional.empty());
        service.upsertAndLinkByNaturalKey(LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).payerCode("CNAMU").policyNumber("AMU-1")
            .providerName("  Caisse Nationale AMU ").build(), ctx, locale);

        PatientInsurance existing = new PatientInsurance();
        existing.setProviderName("Assurance Faso");
        when(patientInsuranceRepository
                .findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                    patientId, hospitalId, "CNAMU", "AMU-2")).thenReturn(Optional.of(existing));
        service.upsertAndLinkByNaturalKey(LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).payerCode("CNAMU").policyNumber("AMU-2").build(),
            ctx, locale);

        org.mockito.ArgumentCaptor<PatientInsurance> saved = org.mockito.ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PatientInsurance::getProviderName)
            .contains("Caisse Nationale AMU", "Assurance Faso");
    }

    /**
     * Review of #1131 (the D1590 class): hospital A holds the policy; B's desk
     * links the same policy. A's row is untouched (still A's, still primary)
     * and B gets its own row, primary at B.
     */
    @Test
    void upsertAndLinkByNaturalKey_anotherHospitalsRowIsNeverRetagged_review1131() {
        com.example.hms.model.Hospital hospitalA = new com.example.hms.model.Hospital();
        hospitalA.setId(UUID.randomUUID());
        com.example.hms.model.Hospital hospitalB = new com.example.hms.model.Hospital();
        hospitalB.setId(hospitalId);
        UserRoleHospitalAssignment atA = new UserRoleHospitalAssignment();
        atA.setHospital(hospitalA);
        UserRoleHospitalAssignment atB = new UserRoleHospitalAssignment();
        atB.setHospital(hospitalB);
        PatientInsurance rowA = new PatientInsurance();
        rowA.setId(UUID.randomUUID());
        rowA.setPatient(patient);
        rowA.setAssignment(atA);
        rowA.setPrimary(true);
        rowA.setPayerCode("CNAMU");
        rowA.setPolicyNumber("AMU-9");
        rowA.setProviderName("Caisse Nationale AMU");
        rowA.setLinkedAs("STAFF");

        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(atB));
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(any())).thenReturn(new PatientInsuranceResponseDTO());

        // An in-memory table holding A's row. Every policy finder answers by its
        // own name: hospital-scoped, untagged-only, or (the old lookup) any row.
        java.util.List<PatientInsurance> table = new java.util.ArrayList<>(List.of(rowA));
        java.util.List<UUID> primaryResets = new java.util.ArrayList<>();
        PatientInsuranceRepository fake = org.mockito.Mockito.mock(PatientInsuranceRepository.class, inv -> {
            String name = inv.getMethod().getName();
            if (name.equals("save")) {
                PatientInsurance row = inv.getArgument(0);
                if (table.stream().noneMatch(r -> r == row)) table.add(row);
                return row;
            }
            if (name.equals("unsetOtherPrimariesForPatientInHospital")) {
                primaryResets.add(inv.getArgument(2));
                return 0;
            }
            if (name.startsWith("find") && name.contains("PolicyNumber")) {
                return table.stream().filter(r -> {
                    UUID heldBy = r.getAssignment() == null ? null : r.getAssignment().getHospital().getId();
                    if (name.contains("Assignment_Hospital_Id")) return inv.getArgument(1).equals(heldBy);
                    if (name.contains("AssignmentIsNull")) return heldBy == null;
                    return true;
                }).findFirst();
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(inv);
        });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "patientInsuranceRepository", fake);

        service.upsertAndLinkByNaturalKey(LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).payerCode("CNAMU").policyNumber("AMU-9")
            .primary(true).build(), ctx, locale);

        assertThat(table).hasSize(2);
        PatientInsurance rowB = table.get(1);
        assertThat(rowB).isNotSameAs(rowA);
        assertThat(rowB.getAssignment()).isSameAs(atB);
        assertThat(rowB.isPrimary()).isTrue();
        // A's row is where it was, as it was.
        assertThat(rowA.getAssignment()).isSameAs(atA);
        assertThat(rowA.isPrimary()).isTrue();
        // The primary reset is B's only.
        assertThat(primaryResets).containsOnly(hospitalId);
    }

    @Test
    void linkPatientInsurance_staffCannotRetagAnotherHospitalsRow_review1131() {
        com.example.hms.model.Hospital hospitalA = new com.example.hms.model.Hospital();
        hospitalA.setId(UUID.randomUUID());
        com.example.hms.model.Hospital hospitalB = new com.example.hms.model.Hospital();
        hospitalB.setId(hospitalId);
        UserRoleHospitalAssignment atA = new UserRoleHospitalAssignment();
        atA.setHospital(hospitalA);
        UserRoleHospitalAssignment atB = new UserRoleHospitalAssignment();
        atB.setHospital(hospitalB);
        PatientInsurance rowA = new PatientInsurance();
        rowA.setId(insuranceId);
        rowA.setAssignment(atA);
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);

        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(rowA));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(atB));

        assertThatThrownBy(() -> service.linkPatientInsurance(insuranceId, LinkPatientInsuranceRequestDTO.builder()
                .patientId(patientId).hospitalId(hospitalId).build(), ctx, locale))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(rowA.getAssignment()).isSameAs(atA);
        verify(patientInsuranceRepository, org.mockito.Mockito.never()).save(any());
    }

    /** Review of #1131: B's desk can neither edit nor delete the row hospital A holds; both answer 404. */
    @Test
    void updateAndDelete_anotherHospitalsRowAnswersAsMissing_review1131() {
        com.example.hms.model.Hospital hospitalA = new com.example.hms.model.Hospital();
        hospitalA.setId(UUID.randomUUID());
        UserRoleHospitalAssignment atA = new UserRoleHospitalAssignment();
        atA.setHospital(hospitalA);
        PatientInsurance rowA = new PatientInsurance();
        rowA.setId(insuranceId);
        rowA.setPatient(patient);
        rowA.setAssignment(atA);
        rowA.setProviderName("Caisse Nationale AMU");
        rowA.setPolicyNumber("AMU-9");
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(rowA));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        PatientInsuranceRequestDTO edit = new PatientInsuranceRequestDTO();
        edit.setProviderName("Autre assureur");
        assertThatThrownBy(() -> service.updatePatientInsurance(insuranceId, edit, locale))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deletePatientInsurance(insuranceId, locale))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);

        verify(patientInsuranceMapper, org.mockito.Mockito.never()).updateEntityFromDto(any(), any(), any());
        verify(patientInsuranceRepository, org.mockito.Mockito.never()).save(any());
        verify(patientInsuranceRepository, org.mockito.Mockito.never()).deleteById(any());
        assertThat(rowA.getAssignment()).isSameAs(atA);
        assertThat(rowA.getProviderName()).isEqualTo("Caisse Nationale AMU");
    }

    /**
     * QA D1651: B's staff read hospital A's row by id (200, with A's payer and
     * policy). A read by id now answers exactly as a missing id, and B's list
     * holds only B's row and the legacy untagged one. Owner decision
     * 2026-11-22 keeps this: B sees A's policy only by its identity, to verify,
     * through its own read (PatientInsuranceOtherHospitalTest), never this row.
     */
    @Test
    void readByIdAndList_anotherHospitalsRowIsHidden_qaD1651() {
        com.example.hms.model.Hospital hospitalA = new com.example.hms.model.Hospital();
        hospitalA.setId(UUID.randomUUID());
        UserRoleHospitalAssignment atA = new UserRoleHospitalAssignment();
        atA.setHospital(hospitalA);
        com.example.hms.model.Hospital hospitalB = new com.example.hms.model.Hospital();
        hospitalB.setId(hospitalId);
        UserRoleHospitalAssignment atB = new UserRoleHospitalAssignment();
        atB.setHospital(hospitalB);
        PatientInsurance rowA = new PatientInsurance();
        rowA.setId(insuranceId);
        rowA.setPatient(patient);
        rowA.setAssignment(atA);
        PatientInsurance rowB = new PatientInsurance();
        rowB.setId(UUID.randomUUID());
        rowB.setPatient(patient);
        rowB.setAssignment(atB);
        PatientInsurance legacy = new PatientInsurance();
        legacy.setId(UUID.randomUUID());
        legacy.setPatient(patient);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(rowA));
        when(patientInsuranceRepository.findByPatient_Id(patientId)).thenReturn(List.of(rowA, rowB, legacy));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(patientChartAccess.require(patientId, hospitalId)).thenReturn(patient);
        PatientInsuranceResponseDTO dtoB = new PatientInsuranceResponseDTO();
        PatientInsuranceResponseDTO dtoLegacy = new PatientInsuranceResponseDTO();
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(rowB)).thenReturn(dtoB);
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(legacy)).thenReturn(dtoLegacy);
        // Review of #1202: a list maps in one call (its plans' names read once).
        when(patientInsuranceMapper.toPatientInsuranceResponseDTOs(org.mockito.ArgumentMatchers.anyList()))
            .thenAnswer(call -> ((List<PatientInsurance>) call.getArgument(0)).stream()
                .map(patientInsuranceMapper::toPatientInsuranceResponseDTO).toList());

        assertThatThrownBy(() -> service.getPatientInsuranceById(insuranceId, locale))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(service.getInsurancesByPatientId(patientId, locale)).containsExactly(dtoB, dtoLegacy);
        verify(patientInsuranceMapper, org.mockito.Mockito.never()).toPatientInsuranceResponseDTO(rowA);
    }

    /** Review of #1131: this hospital's row, and a legacy row tagged to no hospital, stay writable. */
    @Test
    void delete_thisHospitalsRowAndAnUntaggedRowStayWritable_review1131() {
        com.example.hms.model.Hospital hospitalB = new com.example.hms.model.Hospital();
        hospitalB.setId(hospitalId);
        UserRoleHospitalAssignment atB = new UserRoleHospitalAssignment();
        atB.setHospital(hospitalB);
        PatientInsurance rowB = new PatientInsurance();
        rowB.setId(insuranceId);
        rowB.setPatient(patient);
        rowB.setAssignment(atB);
        UUID legacyId = UUID.randomUUID();
        PatientInsurance legacy = new PatientInsurance();
        legacy.setId(legacyId);
        legacy.setPatient(patient);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(rowB));
        when(patientInsuranceRepository.findById(legacyId)).thenReturn(Optional.of(legacy));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        service.deletePatientInsurance(insuranceId, locale);
        service.deletePatientInsurance(legacyId, locale);

        verify(patientInsuranceRepository).deleteById(insuranceId);
        verify(patientInsuranceRepository).deleteById(legacyId);
    }

    @Test
    void upsertAndLinkByNaturalKey_missingPayerCode_throwsBusiness() {
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).policyNumber("POL123").build();
        ActingContext ctx = new ActingContext(userId, hospitalId, ActingMode.STAFF, null);

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
        when(subjectReadGuard.callerOwns(patient)).thenReturn(true);

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
        when(subjectReadGuard.callerOwns(otherPatient)).thenReturn(false);

        String refused = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.addInsuranceToPatient(dto, locale)).getMessage();
        when(patientRepository.findById(otherPatient.getId())).thenReturn(Optional.empty());
        String missing = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.addInsuranceToPatient(dto, locale)).getMessage();
        assertThat(refused).isEqualTo(missing);
        verify(patientInsuranceRepository, never()).save(any());
    }

    // ── Billing-money plan PR 6: the "Formule" picker (PUT /patient-insurances/{id}/payer-plan) ──

    private PatientInsurance rowAtThisHospital() {
        com.example.hms.model.Hospital here = new com.example.hms.model.Hospital();
        here.setId(hospitalId);
        UserRoleHospitalAssignment desk = new UserRoleHospitalAssignment();
        desk.setHospital(here);
        PatientInsurance row = new PatientInsurance();
        row.setId(insuranceId);
        row.setPatient(patient);
        row.setAssignment(desk);
        return row;
    }

    private void receptionAtThisHospital(boolean mayLinkPlans) {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(subjectReadGuard.callerUserId()).thenReturn(Optional.of(userId));
        when(roleValidator.canLinkPayerPlan(userId, hospitalId)).thenReturn(mayLinkPlans);
    }

    @Test
    void setPayerPlan_linksAPlanOfTheHospitalAndAuditsIt() {
        PatientInsurance row = rowAtThisHospital();
        UUID planId = UUID.randomUUID();
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(row));
        when(patientInsuranceRepository.save(row)).thenReturn(row);
        PatientInsuranceResponseDTO dto = new PatientInsuranceResponseDTO();
        when(patientInsuranceMapper.toPatientInsuranceResponseDTO(row)).thenReturn(dto);

        assertThat(service.setPayerPlan(insuranceId, planId, locale)).isSameAs(dto);

        assertThat(row.getPayerPlanId()).isEqualTo(planId);
        verify(payerCatalogueService).requireLinkablePlan(planId, hospitalId);
        verify(payerCatalogueService).auditCoverageLink(insuranceId, planId, hospitalId, userId);
    }

    @Test
    void setPayerPlan_aPlanOfAnotherHospitalAnswersAsAMissingOne() {
        PatientInsurance row = rowAtThisHospital();
        UUID foreignPlan = UUID.randomUUID();
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(row));
        when(payerCatalogueService.requireLinkablePlan(foreignPlan, hospitalId))
            .thenThrow(new ResourceNotFoundException("billing.payerPlan.notFound", foreignPlan));

        assertThatThrownBy(() -> service.setPayerPlan(insuranceId, foreignPlan, locale))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(row.getPayerPlanId()).isNull();
        verify(patientInsuranceRepository, never()).save(any());
        verify(payerCatalogueService, never()).auditCoverageLink(any(), any(), any(), any());
    }

    @Test
    void setPayerPlan_aCallerWithoutThePlanLinkRoleIsRefusedBeforeAnyLookup() {
        receptionAtThisHospital(false);
        assertThatThrownBy(() -> service.setPayerPlan(insuranceId, UUID.randomUUID(), locale))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(patientInsuranceRepository, never()).findById(any());
        verify(payerCatalogueService, never()).requireLinkablePlan(any(), any());
    }

    @Test
    void setPayerPlan_anotherHospitalsRowAnswersAsAMissingOne() {
        com.example.hms.model.Hospital elsewhere = new com.example.hms.model.Hospital();
        elsewhere.setId(UUID.randomUUID());
        UserRoleHospitalAssignment atB = new UserRoleHospitalAssignment();
        atB.setHospital(elsewhere);
        PatientInsurance rowB = new PatientInsurance();
        rowB.setId(insuranceId);
        rowB.setAssignment(atB);
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(rowB));
        UUID anyPlan = UUID.randomUUID();

        assertThatThrownBy(() -> service.setPayerPlan(insuranceId, anyPlan, locale))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(rowB.getPayerPlanId()).isNull();
        verify(payerCatalogueService, never()).requireLinkablePlan(any(), any());
    }

    @Test
    void setPayerPlan_aSharedRowGetsACopyForTheActingHospital_andStaysShared() {
        PatientInsurance legacy = new PatientInsurance();
        legacy.setId(insuranceId);
        legacy.setPatient(patient);
        UUID planId = UUID.randomUUID();
        com.example.hms.model.Hospital here = new com.example.hms.model.Hospital();
        here.setId(hospitalId);
        UserRoleHospitalAssignment desk = new UserRoleHospitalAssignment();
        desk.setHospital(here);
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(legacy));
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId))
            .thenReturn(Optional.of(desk));
        when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(call -> call.getArgument(0));

        service.setPayerPlan(insuranceId, planId, locale);

        // Review of #1202: hospital B still sees the shared row as it was; A's plan is on A's own copy.
        assertThat(legacy.getAssignment()).isNull();
        assertThat(legacy.getPayerPlanId()).isNull();
        org.mockito.ArgumentCaptor<PatientInsurance> saved = org.mockito.ArgumentCaptor.forClass(PatientInsurance.class);
        verify(patientInsuranceRepository).save(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(legacy);
        assertThat(saved.getValue().getAssignment()).isSameAs(desk);
        assertThat(saved.getValue().getPayerPlanId()).isEqualTo(planId);
        assertThat(saved.getValue().getPatient()).isSameAs(patient);
        verify(patientChartAccess).require(patientId, hospitalId);
    }

    @Test
    void setPayerPlan_nullRemovesTheLinkAndAuditsIt() {
        PatientInsurance row = rowAtThisHospital();
        UUID oldPlan = UUID.randomUUID();
        row.setPayerPlanId(oldPlan);
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(row));
        when(patientInsuranceRepository.save(row)).thenReturn(row);

        service.setPayerPlan(insuranceId, null, locale);

        assertThat(row.getPayerPlanId()).isNull();
        verify(payerCatalogueService, never()).requireLinkablePlan(any(), any());
        verify(payerCatalogueService).auditCoverageLink(insuranceId, null, hospitalId, userId);
    }

    @Test
    void aPatientNeverPicksThePlanTheirCoverageIsBilledUnder() {
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(patientId).payerCode("MUT-TEST-A").policyNumber("MTA-TEST-0002")
            .payerPlanId(UUID.randomUUID()).build();
        ActingContext ctx = new ActingContext(userId, null, ActingMode.PATIENT, null);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(subjectReadGuard.callerOwns(patient)).thenReturn(true);

        assertThatThrownBy(() -> service.upsertAndLinkByNaturalKey(req, ctx, locale))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("payerPlanId"));
        verify(patientInsuranceRepository, never()).save(any());
    }

    @Test
    void anEditNamingAPlanNeedsThePlanLinkRole() {
        PatientInsurance row = rowAtThisHospital();
        receptionAtThisHospital(false);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(row));
        PatientInsuranceRequestDTO edit = new PatientInsuranceRequestDTO();
        edit.setPayerPlanId(UUID.randomUUID());

        assertThatThrownBy(() -> service.updatePatientInsurance(insuranceId, edit, locale))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(row.getPayerPlanId()).isNull();
        verify(patientInsuranceRepository, never()).save(any());
    }

    // ── Review of #1202: tenancy of the plan link ──

    @Test
    void setPayerPlan_aPatientThisHospitalMayNotChartAnswersAsMissing() {
        PatientInsurance legacy = new PatientInsurance();
        legacy.setId(insuranceId);
        legacy.setPatient(patient);
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(legacy));
        when(patientChartAccess.require(patientId, hospitalId))
            .thenThrow(new ResourceNotFoundException("patient.notFound", patientId));
        UUID planId = UUID.randomUUID();

        assertThatThrownBy(() -> service.setPayerPlan(insuranceId, planId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientInsuranceRepository, never()).save(any());
        verify(payerCatalogueService, never()).requireLinkablePlan(any(), any());
        assertThat(legacy.getAssignment()).isNull();
    }

    @Test
    void anEditNeverChangesTheHospitalTag_aSharedRowIsSentToThePicker() {
        PatientInsurance legacy = new PatientInsurance();
        legacy.setId(insuranceId);
        legacy.setPatient(patient);
        receptionAtThisHospital(true);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(legacy));
        PatientInsuranceRequestDTO edit = new PatientInsuranceRequestDTO();
        edit.setPayerPlanId(UUID.randomUUID());

        assertThatThrownBy(() -> service.updatePatientInsurance(insuranceId, edit, locale))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("payerPlanId"));
        assertThat(legacy.getAssignment()).isNull();
        verify(patientInsuranceRepository, never()).save(any());
    }

    @Test
    void namingThePlanACoverageAlreadyHasNeedsNoPlanRole() {
        PatientInsurance row = rowAtThisHospital();
        UUID current = UUID.randomUUID();
        row.setPayerPlanId(current);
        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.of(row));
        when(patientInsuranceRepository.save(row)).thenReturn(row);
        PatientInsuranceRequestDTO edit = new PatientInsuranceRequestDTO();
        edit.setPayerPlanId(current);

        service.updatePatientInsurance(insuranceId, edit, locale);

        verify(roleValidator, never()).canLinkPayerPlan(any(), any());
        verify(payerCatalogueService, never()).auditCoverageLink(any(), any(), any(), any());
    }
}
