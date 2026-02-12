package com.example.hms.service;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabOrderServiceImplTest {

    @Mock
    private LabOrderRepository labOrderRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock
    private LabOrderMapper labOrderMapper;
    @Mock
    private RoleValidator roleValidator;
    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;

    @InjectMocks
    private LabOrderServiceImpl labOrderService;

    private UUID patientId;
    private UUID staffId;
    private UUID hospitalId;
    private UUID assignmentId;
    private UUID labTestDefinitionId;
    private UUID orderingUserId;

    private Patient patient;
    private Staff staff;
    private Hospital hospital;
    private LabTestDefinition labTestDefinition;
    private UserRoleHospitalAssignment assignment;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        labTestDefinitionId = UUID.randomUUID();
        orderingUserId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        Role role = new Role();
        role.setCode("ROLE_DOCTOR");

        User user = new User();
        user.setId(orderingUserId);

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(assignmentId);
        assignment.setHospital(hospital);
        assignment.setUser(user);
        assignment.setRole(role);

        staff = new Staff();
        staff.setId(staffId);
        staff.setUser(user);
        staff.setHospital(hospital);
        staff.setAssignment(assignment);
        staff.setLicenseNumber("LIC-12345");
        staff.setEmploymentType(EmploymentType.FULL_TIME);
        staff.setJobTitle(JobTitle.DOCTOR);
        staff.setNpi("1234567890");

        labTestDefinition = new LabTestDefinition();
        labTestDefinition.setId(labTestDefinitionId);
        labTestDefinition.setName("CBC Panel");
        labTestDefinition.setTestCode("CBC");
    }

    @Test
    void createLabOrderRequiresDocumentationReferenceWhenShared() {
        mockCommonLookups();
        LabOrderRequestDTO request = baseRequestBuilder()
            .documentationSharedWithLab(true)
            .documentationReference(null)
            .build();

        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Documentation reference is required");

        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrderRequiresOrderChannelOtherWhenChannelIsOther() {
        mockCommonLookups();
        LabOrderRequestDTO request = baseRequestBuilder()
            .orderChannel(LabOrderChannel.OTHER.name())
            .orderChannelOther(null)
            .build();

        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("orderChannelOther must be provided");

        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrderHashesProviderSignaturePayload() {
        mockCommonLookups();
        String signaturePayload = "signed-by-dr";
        LabOrderRequestDTO request = baseRequestBuilder()
            .documentationSharedWithLab(true)
            .providerSignature(signaturePayload)
            .build();

        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LabOrderResponseDTO responseDTO = LabOrderResponseDTO.builder().id(UUID.randomUUID().toString()).build();
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(responseDTO);

        LabOrderResponseDTO result = labOrderService.createLabOrder(request, Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        LabOrder saved = captor.getValue();

        assertThat(saved.getProviderSignatureDigest()).isEqualTo(sha256Hex(signaturePayload));
        assertThat(saved.getSignedByUserId()).isEqualTo(orderingUserId);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void updateLabOrderRetainsExistingSignatureWhenPayloadMissing() {
        mockCommonLookups();
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        String existingDigest = "existing-digest";
        LocalDateTime existingSignedAt = LocalDateTime.now().minusDays(1);
        existing.setProviderSignatureDigest(existingDigest);
        existing.setSignedAt(existingSignedAt);

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LabOrderResponseDTO responseDTO = LabOrderResponseDTO.builder().id(labOrderId.toString()).build();
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(responseDTO);

        LabOrderRequestDTO request = baseRequestBuilder()
            .id(labOrderId)
            .providerSignature(null)
            .documentationSharedWithLab(true)
            .build();

        LabOrderResponseDTO result = labOrderService.updateLabOrder(labOrderId, request, Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        LabOrder saved = captor.getValue();

        assertThat(saved.getProviderSignatureDigest()).isEqualTo(existingDigest);
        assertThat(saved.getSignedAt()).isEqualTo(existingSignedAt);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createLabOrderAppliesStandingOrderMetadata() {
        mockCommonLookups();
        LocalDateTime orderDate = LocalDateTime.now();
        LocalDateTime expiresAt = orderDate.plusDays(30);
        LocalDateTime lastReviewedAt = LocalDateTime.now();
        int reviewIntervalDays = 15;
        String reviewNotes = "Review in two weeks";

        LabOrderRequestDTO request = baseRequestBuilder()
            .orderDatetime(orderDate)
            .standingOrder(true)
            .standingOrderExpiresAt(expiresAt)
            .standingOrderLastReviewedAt(lastReviewedAt)
            .standingOrderReviewIntervalDays(reviewIntervalDays)
            .standingOrderReviewNotes(reviewNotes)
            .build();

        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LabOrderResponseDTO responseDTO = LabOrderResponseDTO.builder().id(UUID.randomUUID().toString()).build();
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(responseDTO);

        labOrderService.createLabOrder(request, Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        LabOrder saved = captor.getValue();

        assertThat(saved.isStandingOrder()).isTrue();
        assertThat(saved.getStandingOrderExpiresAt()).isEqualTo(expiresAt);
        assertThat(saved.getStandingOrderLastReviewedAt()).isEqualTo(lastReviewedAt);
        assertThat(saved.getStandingOrderReviewIntervalDays()).isEqualTo(reviewIntervalDays);
        assertThat(saved.getStandingOrderReviewDueAt()).isEqualTo(lastReviewedAt.plusDays(reviewIntervalDays));
        assertThat(saved.getStandingOrderReviewNotes()).isEqualTo(reviewNotes);
    }

    private void mockCommonLookups() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(roleValidator.canOrderLabTests(orderingUserId, hospitalId)).thenReturn(true);
        when(labTestDefinitionRepository.findById(labTestDefinitionId)).thenReturn(Optional.of(labTestDefinition));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        lenient().when(labOrderRepository.existsByPatient_IdAndLabTestDefinition_IdAndOrderDatetime(eq(patientId), eq(labTestDefinitionId), any(LocalDateTime.class)))
            .thenReturn(false);
    }

    private LabOrderRequestDTO.LabOrderRequestDTOBuilder baseRequestBuilder() {
        return LabOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .orderingStaffId(staffId)
            .labTestDefinitionId(labTestDefinitionId)
            .assignmentId(assignmentId)
            .testName("Complete Blood Count")
            .status(LabOrderStatus.ORDERED.name())
            .clinicalIndication("Rule out anemia")
            .medicalNecessityNote("Needed for diagnosis")
            .notes("Patient fasting")
            .primaryDiagnosisCode("A01.1")
            .additionalDiagnosisCodes(List.of("B20"))
            .orderChannel(LabOrderChannel.ELECTRONIC.name())
            .documentationSharedWithLab(true)
            .documentationReference("DOC-123")
            .orderingProviderNpi(null)
            .providerSignature("signed-by-dr")
            .standingOrder(false)
            .orderDatetime(LocalDateTime.now());
    }

    private LabOrder existingLabOrder(UUID id) {
        LabOrder labOrder = LabOrder.builder()
            .patient(patient)
            .orderingStaff(staff)
            .hospital(hospital)
            .assignment(assignment)
            .labTestDefinition(labTestDefinition)
            .orderDatetime(LocalDateTime.now().minusDays(2))
            .status(LabOrderStatus.ORDERED)
            .clinicalIndication("Baseline test")
            .primaryDiagnosisCode("A01.1")
            .orderChannel(LabOrderChannel.ELECTRONIC)
            .documentationSharedWithLab(true)
            .standingOrder(false)
            .additionalDiagnosisCodes(List.of("B20"))
            .build();
        labOrder.setId(id);
        labOrder.setSignedByUserId(orderingUserId);
        return labOrder;
    }

    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
