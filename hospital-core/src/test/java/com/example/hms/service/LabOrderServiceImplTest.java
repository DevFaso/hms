package com.example.hms.service;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import java.util.Map;
import java.util.Set;

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
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.lab.LabOrderRoutingNotifier routingNotifier;
    @Mock private com.example.hms.controller.support.ControllerAuthUtils authUtils;

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
    void createLabOrderSucceedsWithoutDocumentationReferenceWhenShared() {
        mockCommonLookups();
        LabOrderRequestDTO request = baseRequestBuilder()
            .documentationSharedWithLab(true)
            .documentationReference(null)
            .build();

        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LabOrderResponseDTO responseDTO = LabOrderResponseDTO.builder().id(UUID.randomUUID().toString()).build();
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(responseDTO);

        LabOrderResponseDTO result = labOrderService.createLabOrder(request, Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        LabOrder saved = captor.getValue();

        assertThat(saved.isDocumentationSharedWithLab()).isTrue();
        assertThat(saved.getDocumentationReference()).isNull();
        assertThat(result).isSameAs(responseDTO);
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
    void createLabOrderKeepsAStartStatusTheCallerChose() {
        // SuperAdminLabOrderServiceImpl validates status as a mandatory field
        // and passes it through, and OrderSetItemDispatcher places order-set
        // items as PENDING. Forcing every create to ORDERED discarded the
        // first and made the second log a warning for every order it placed.
        mockCommonLookups();
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class)))
            .thenReturn(LabOrderResponseDTO.builder().build());

        for (LabOrderStatus requested : List.of(LabOrderStatus.ORDERED, LabOrderStatus.PENDING)) {
            labOrderService.createLabOrder(baseRequestBuilder().status(requested.name()).build(), Locale.ENGLISH);
        }

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LabOrder::getStatus)
            .containsExactly(LabOrderStatus.ORDERED, LabOrderStatus.PENDING);
    }

    @Test
    void createLabOrderRefusesAStatusThatClaimsWorkTheLabHasNotDone() {
        // The B9 half that still matters: an order born COMPLETED or CANCELLED
        // is frozen against every specimen and result event, and a COMPLETED
        // one reaches the review queue with no results behind it.
        mockCommonLookups();

        // CANCELLED belongs here too: an order created cancelled is frozen
        // against every lifecycle event for ever, because nothing re-opens a
        // cancelled order. A caller that wants one records the order and
        // cancels it through the role-checked transition endpoint.
        for (LabOrderStatus requested : List.of(LabOrderStatus.COMPLETED, LabOrderStatus.CANCELLED,
                LabOrderStatus.VERIFIED,
                LabOrderStatus.RESULTED, LabOrderStatus.IN_PROGRESS, LabOrderStatus.RECEIVED,
                LabOrderStatus.COLLECTED)) {
            LabOrderRequestDTO request = baseRequestBuilder().status(requested.name()).build();
            assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(requested.name());
        }
        verify(labOrderRepository, never()).save(any(LabOrder.class));
    }

    @Test
    void updateLabOrderIgnoresARequestedStatusJump() {
        // B9: the edit form echoes `status` back, and a doctor could point it at
        // COMPLETED. On update the current status wins; the lifecycle moves
        // through the transition endpoint and the specimen/result events only.
        mockCommonLookups();
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        existing.setStatus(LabOrderStatus.COLLECTED);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class)))
            .thenReturn(LabOrderResponseDTO.builder().id(labOrderId.toString()).build());

        LabOrderRequestDTO request = baseRequestBuilder()
            .id(labOrderId)
            .status(LabOrderStatus.COMPLETED.name())
            .documentationSharedWithLab(true)
            .build();

        labOrderService.updateLabOrder(labOrderId, request, Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LabOrderStatus.COLLECTED);
    }

    @Test
    void updateLabOrderToleratesTheCurrentStatusEchoedBack() {
        mockCommonLookups();
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        existing.setStatus(LabOrderStatus.RECEIVED);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class)))
            .thenReturn(LabOrderResponseDTO.builder().id(labOrderId.toString()).build());

        LabOrderRequestDTO request = baseRequestBuilder()
            .id(labOrderId)
            .status("received")
            .documentationSharedWithLab(true)
            .build();

        labOrderService.updateLabOrder(labOrderId, request, Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LabOrderStatus.RECEIVED);
    }

    @Test
    void createLabOrderRejectsAnUnknownStatus() {
        mockCommonLookups();
        LabOrderRequestDTO request = baseRequestBuilder().status("FINISHED").build();

        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FINISHED");
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

    @Test
    void createLabOrderUsesUnscopedPatientLookupBeforeHospitalRegistrationCheck() {
        mockCommonLookups();
        LabOrderRequestDTO request = baseRequestBuilder().build();

        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class)))
            .thenReturn(LabOrderResponseDTO.builder().id(UUID.randomUUID().toString()).build());

        labOrderService.createLabOrder(request, Locale.ENGLISH);

        verify(patientRepository).findByIdUnscoped(patientId);
        verify(patientHospitalRegistrationRepository).existsByPatientIdAndHospitalId(patientId, hospitalId);
    }

    @Test
    void createLabOrderThrowsClearScopeErrorWhenPatientIsNotRegisteredAtHospital() {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> labOrderService.createLabOrder(baseRequestBuilder().build(), Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Patient is not registered with the specified hospital.");

        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrderThrowsNotFoundWhenPatientDoesNotExist() {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> labOrderService.createLabOrder(baseRequestBuilder().build(), Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(patientHospitalRegistrationRepository, never()).existsByPatientIdAndHospitalId(any(), any());
        verify(labOrderRepository, never()).save(any());
    }

    // -- The ordering staff is the caller's staff row AT the order's hospital --

    /** A second hospital, and the same doctor's staff row there (uq_staff_user_hospital). */
    private Staff sameDoctorsRowAt(Hospital elsewhere) {
        Staff row = new Staff();
        row.setId(UUID.randomUUID());
        row.setUser(staff.getUser());
        row.setHospital(elsewhere);
        return row;
    }

    private Hospital otherHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        other.setName("Hopital B");
        return other;
    }

    /** The row the request NAMES is where the doctor is primary; the order is at hospitalId. */
    private void givenTheRequestNamesTheRowAt(Staff namedRow) {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findById(namedRow.getId())).thenReturn(Optional.of(namedRow));
    }

    @Test
    void createLabOrder_bindsTheOrderToTheCallersStaffRowAtTheOrdersHospital() {
        Staff primaryRowElsewhere = sameDoctorsRowAt(otherHospital());
        givenTheRequestNamesTheRowAt(primaryRowElsewhere);
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(orderingUserId));
        when(staffRepository.findByUserIdAndHospitalId(orderingUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(roleValidator.canOrderLabTests(orderingUserId, hospitalId)).thenReturn(true);
        when(labTestDefinitionRepository.findById(labTestDefinitionId)).thenReturn(Optional.of(labTestDefinition));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        labOrderService.createLabOrder(baseRequestBuilder().orderingStaffId(primaryRowElsewhere.getId()).build(),
            Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderingStaff())
            .as("the row at the order's hospital, never the one at the other hospital")
            .isSameAs(staff);
    }

    @Test
    void createLabOrder_refusesWhenTheCallerHasNoStaffRowAtTheOrdersHospital() {
        Staff primaryRowElsewhere = sameDoctorsRowAt(otherHospital());
        givenTheRequestNamesTheRowAt(primaryRowElsewhere);
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(orderingUserId));
        when(staffRepository.findByUserIdAndHospitalId(orderingUserId, hospitalId)).thenReturn(Optional.empty());

        LabOrderRequestDTO request = baseRequestBuilder().orderingStaffId(primaryRowElsewhere.getId()).build();
        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("staff.notfound");
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrder_refusesAnotherClinicianNamedAsTheOrderingStaff() {
        givenTheRequestNamesTheRowAt(staff);
        // Someone else is signed in and names this doctor's staff id.
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));

        LabOrderRequestDTO request = baseRequestBuilder().build();
        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("staff.notfound");
        verify(roleValidator, never()).canOrderLabTests(any(), any());
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrder_aSuperAdminMayNameTheClinician_butStillOnlyTheirRowAtTheOrdersHospital() {
        Staff primaryRowElsewhere = sameDoctorsRowAt(otherHospital());
        givenTheRequestNamesTheRowAt(primaryRowElsewhere);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(orderingUserId, hospitalId)).thenReturn(Optional.empty());

        LabOrderRequestDTO request = baseRequestBuilder().orderingStaffId(primaryRowElsewhere.getId()).build();
        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(authUtils, never()).resolveUserId(any());
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void updateLabOrder_anotherClinicianMayEditWithoutTakingOverTheOrder() {
        // A nurse corrects a doctor's order and leaves the doctor as the
        // ordering clinician: the placing rule (the named clinician must be
        // the caller) is not an edit rule, and it used to answer 404.
        mockCommonLookups();
        lenient().when(authUtils.resolveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        labOrderService.updateLabOrder(labOrderId, baseRequestBuilder().id(labOrderId).build(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderingStaff()).isSameAs(staff);
    }

    @Test
    void updateLabOrder_namingSomeoneNewStillFollowsThePlacingRule() {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        Staff otherDoctor = new Staff();
        otherDoctor.setId(UUID.randomUUID());
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        otherDoctor.setUser(otherUser);
        otherDoctor.setHospital(hospital);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(staffRepository.findById(otherDoctor.getId())).thenReturn(Optional.of(otherDoctor));

        LabOrderRequestDTO request = baseRequestBuilder().id(labOrderId).orderingStaffId(otherDoctor.getId()).build();
        assertThatThrownBy(() -> labOrderService.updateLabOrder(labOrderId, request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labOrderRepository, never()).save(any());
    }

    // -- The recorded assignment is the ordering clinician's, active, at the order's hospital --

    private UserRoleHospitalAssignment assignmentOf(User holder, Hospital at, boolean active) {
        UserRoleHospitalAssignment row = new UserRoleHospitalAssignment();
        row.setId(UUID.randomUUID());
        row.setUser(holder);
        row.setHospital(at);
        row.setRole(assignment.getRole());
        row.setActive(active);
        return row;
    }

    private User someoneElse() {
        User other = new User();
        other.setId(UUID.randomUUID());
        return other;
    }

    private LabOrder createWithAssignment(UUID requestedAssignmentId) {
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        labOrderService.createLabOrder(baseRequestBuilder().assignmentId(requestedAssignmentId).build(), Locale.ENGLISH);
        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertCreateRefusedAsUnknownAssignment(UUID requestedAssignmentId) {
        LabOrderRequestDTO request = baseRequestBuilder().assignmentId(requestedAssignmentId).build();
        assertThatThrownBy(() -> labOrderService.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("assignment.notfound");
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrder_recordsTheNamedAssignmentWhenItIsTheClinicians() {
        mockCommonLookups();

        assertThat(createWithAssignment(assignmentId).getAssignment()).isSameAs(assignment);
        verify(assignmentRepository, never()).findByUser_IdAndActiveTrue(any());
    }

    @Test
    void createLabOrder_refusesAnotherPersonsAssignment() {
        mockCommonLookups();
        UserRoleHospitalAssignment theirs = assignmentOf(someoneElse(), hospital, true);
        when(assignmentRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertCreateRefusedAsUnknownAssignment(theirs.getId());
    }

    @Test
    void createLabOrder_refusesAnotherPersonsAssignmentAtAnotherHospital() {
        mockCommonLookups();
        UserRoleHospitalAssignment theirs = assignmentOf(someoneElse(), otherHospital(), true);
        when(assignmentRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertCreateRefusedAsUnknownAssignment(theirs.getId());
    }

    @Test
    void createLabOrder_refusesAnotherPersonsInactiveAssignment() {
        mockCommonLookups();
        UserRoleHospitalAssignment theirs = assignmentOf(someoneElse(), hospital, false);
        when(assignmentRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        assertCreateRefusedAsUnknownAssignment(theirs.getId());
    }

    @Test
    void createLabOrder_refusesAnUnknownAssignmentEvenWhenOneCouldBeDerived() {
        // Same answer as another person's id: deriving here would tell a
        // caller which ids exist (refused) and which do not (accepted).
        mockCommonLookups();
        UUID unknown = UUID.randomUUID();
        when(assignmentRepository.findById(unknown)).thenReturn(Optional.empty());

        assertCreateRefusedAsUnknownAssignment(unknown);
    }

    @Test
    void createLabOrder_derivesTheAssignmentWhenNoneIsNamed() {
        // Internal callers may pass no id; the DTO's @NotNull keeps HTTP callers out of this path.
        mockCommonLookups();

        assertThat(createWithAssignment(null).getAssignment()).isSameAs(assignment);
        verify(assignmentRepository, never()).findById(any());
    }

    private UserRoleHospitalAssignment adminAssignmentHere(UUID id) {
        Role admin = new Role();
        admin.setCode("ROLE_HOSPITAL_ADMIN");
        UserRoleHospitalAssignment row = assignmentOf(staff.getUser(), hospital, true);
        row.setId(id);
        row.setRole(admin);
        return row;
    }

    @Test
    void createLabOrder_replacesTheCliniciansOwnNonClinicalAssignmentHereWithTheLabOrderingOne() {
        mockCommonLookups();
        UserRoleHospitalAssignment adminHere = adminAssignmentHere(UUID.randomUUID());
        when(assignmentRepository.findById(adminHere.getId())).thenReturn(Optional.of(adminHere));

        assertThat(createWithAssignment(adminHere.getId()).getAssignment()).isSameAs(assignment);
    }

    @Test
    void createLabOrder_theFallbackSkipsANonClinicalAssignmentEvenWhenItsIdSortsFirst() {
        mockCommonLookups();
        assignment.setActive(false);
        UserRoleHospitalAssignment adminHere = adminAssignmentHere(new UUID(0L, 1L));
        UserRoleHospitalAssignment doctorHere = assignmentOf(staff.getUser(), hospital, true);
        doctorHere.setId(new UUID(0L, 2L));
        when(assignmentRepository.findByUser_IdAndActiveTrue(orderingUserId)).thenReturn(List.of(adminHere, doctorHere));

        assertThat(createWithAssignment(assignmentId).getAssignment()).isSameAs(doctorHere);
    }

    @Test
    void createLabOrder_refusesWhenTheCliniciansOnlyActiveAssignmentHereIsNonClinical() {
        mockCommonLookups();
        assignment.setActive(false);
        UserRoleHospitalAssignment adminHere = adminAssignmentHere(UUID.randomUUID());
        when(assignmentRepository.findByUser_IdAndActiveTrue(orderingUserId)).thenReturn(List.of(adminHere));

        assertCreateRefusedAsUnknownAssignment(assignmentId);
    }

    @Test
    void createLabOrder_replacesTheCliniciansOwnAssignmentAtAnotherHospitalWithTheOneHeldHere() {
        // The portal sends the signed-in user's first active assignment; for a
        // clinician working at two hospitals that is often the other one.
        mockCommonLookups();
        UserRoleHospitalAssignment ownElsewhere = assignmentOf(staff.getUser(), otherHospital(), true);
        when(assignmentRepository.findById(ownElsewhere.getId())).thenReturn(Optional.of(ownElsewhere));

        assertThat(createWithAssignment(ownElsewhere.getId()).getAssignment()).isSameAs(assignment);
    }

    @Test
    void createLabOrder_replacesTheCliniciansOwnInactiveAssignmentWithTheActiveOneHere() {
        mockCommonLookups();
        UserRoleHospitalAssignment revoked = assignmentOf(staff.getUser(), hospital, false);
        when(assignmentRepository.findById(revoked.getId())).thenReturn(Optional.of(revoked));

        assertThat(createWithAssignment(revoked.getId()).getAssignment()).isSameAs(assignment);
    }

    @Test
    void createLabOrder_fallsBackToAnyActiveAssignmentHereWhenTheStaffRowsIsInactive() {
        mockCommonLookups();
        assignment.setActive(false);
        UserRoleHospitalAssignment ownElsewhere = assignmentOf(staff.getUser(), otherHospital(), true);
        UserRoleHospitalAssignment activeHere = assignmentOf(staff.getUser(), hospital, true);
        when(assignmentRepository.findById(ownElsewhere.getId())).thenReturn(Optional.of(ownElsewhere));
        when(assignmentRepository.findByUser_IdAndActiveTrue(orderingUserId))
            .thenReturn(List.of(ownElsewhere, activeHere));

        assertThat(createWithAssignment(ownElsewhere.getId()).getAssignment()).isSameAs(activeHere);
    }

    @Test
    void createLabOrder_refusesTheCliniciansOwnAssignmentElsewhereWhenTheyHoldNoneHere() {
        mockCommonLookups();
        assignment.setActive(false);
        UserRoleHospitalAssignment ownElsewhere = assignmentOf(staff.getUser(), otherHospital(), true);
        when(assignmentRepository.findById(ownElsewhere.getId())).thenReturn(Optional.of(ownElsewhere));
        when(assignmentRepository.findByUser_IdAndActiveTrue(orderingUserId)).thenReturn(List.of(ownElsewhere));

        assertCreateRefusedAsUnknownAssignment(ownElsewhere.getId());
    }

    @Test
    void createLabOrder_refusesTheCliniciansOwnInactiveAssignmentWhenTheyHoldNoneActiveHere() {
        mockCommonLookups();
        assignment.setActive(false);
        when(assignmentRepository.findByUser_IdAndActiveTrue(orderingUserId)).thenReturn(List.of());

        assertCreateRefusedAsUnknownAssignment(assignmentId);
    }

    @Test
    void updateLabOrder_anotherClinicianKeepsTheOrdersAssignmentWhenLeftUnchanged() {
        mockCommonLookups();
        lenient().when(authUtils.resolveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        labOrderService.updateLabOrder(labOrderId, baseRequestBuilder().id(labOrderId).build(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getAssignment()).isSameAs(assignment);
        verify(assignmentRepository, never()).findById(any());
    }

    @Test
    void updateLabOrder_theEditorsOwnAssignmentNeverBecomesTheOrdersContext() {
        // A nurse corrects a doctor's order, keeps the doctor, and her client
        // sends her own assignment: the order keeps the doctor's.
        mockCommonLookups();
        User nurse = someoneElse();
        UserRoleHospitalAssignment nursesAssignment = assignmentOf(nurse, hospital, true);
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(nurse.getId()));
        when(assignmentRepository.findById(nursesAssignment.getId())).thenReturn(Optional.of(nursesAssignment));
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        labOrderService.updateLabOrder(labOrderId,
            baseRequestBuilder().id(labOrderId).assignmentId(nursesAssignment.getId()).build(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getAssignment()).isSameAs(assignment);
    }

    @Test
    void updateLabOrder_refusesAThirdPersonsAssignmentOnAKeptOrder() {
        mockCommonLookups();
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
        UserRoleHospitalAssignment theirs = assignmentOf(someoneElse(), hospital, true);
        when(assignmentRepository.findById(theirs.getId())).thenReturn(Optional.of(theirs));
        UUID labOrderId = UUID.randomUUID();
        LabOrder existing = existingLabOrder(labOrderId);
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existing));

        LabOrderRequestDTO request = baseRequestBuilder().id(labOrderId).assignmentId(theirs.getId()).build();
        assertThatThrownBy(() -> labOrderService.updateLabOrder(labOrderId, request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("assignment.notfound");
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void updateLabOrder_takingOverAnOrderDoesNotTakeOverTheFormerCliniciansAssignment() {
        // A second doctor re-attributes the order to herself but echoes the
        // first doctor's assignment: it is someone else's, so it is refused.
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        User secondDoctor = someoneElse();
        Staff secondDoctorsRow = new Staff();
        secondDoctorsRow.setId(UUID.randomUUID());
        secondDoctorsRow.setUser(secondDoctor);
        secondDoctorsRow.setHospital(hospital);
        secondDoctorsRow.setAssignment(assignmentOf(secondDoctor, hospital, true));
        when(staffRepository.findById(secondDoctorsRow.getId())).thenReturn(Optional.of(secondDoctorsRow));
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(secondDoctor.getId()));
        when(roleValidator.canOrderLabTests(secondDoctor.getId(), hospitalId)).thenReturn(true);
        when(labTestDefinitionRepository.findById(labTestDefinitionId)).thenReturn(Optional.of(labTestDefinition));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        UUID labOrderId = UUID.randomUUID();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existingLabOrder(labOrderId)));

        LabOrderRequestDTO request = baseRequestBuilder().id(labOrderId)
            .orderingStaffId(secondDoctorsRow.getId()).assignmentId(assignmentId).build();
        assertThatThrownBy(() -> labOrderService.updateLabOrder(labOrderId, request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("assignment.notfound");
        verify(labOrderRepository, never()).save(any());
    }

    private LabOrder editOwnOrderNaming(UUID requestedAssignmentId) {
        UUID labOrderId = UUID.randomUUID();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(existingLabOrder(labOrderId)));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        labOrderService.updateLabOrder(labOrderId,
            baseRequestBuilder().id(labOrderId).assignmentId(requestedAssignmentId).build(), Locale.ENGLISH);
        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void updateLabOrder_theCliniciansOtherOwnAssignmentDoesNotMoveTheirOrder() {
        // The portal's edit form sends the editor's first active assignment,
        // not the order's: an order placed under this assignment stays there.
        mockCommonLookups();
        UserRoleHospitalAssignment otherOwnHere = assignmentOf(staff.getUser(), hospital, true);
        when(assignmentRepository.findById(otherOwnHere.getId())).thenReturn(Optional.of(otherOwnHere));

        assertThat(editOwnOrderNaming(otherOwnHere.getId()).getAssignment()).isSameAs(assignment);
    }

    @Test
    void updateLabOrder_anOrderWhoseAssignmentWasRevokedTakesTheCliniciansValidOne() {
        mockCommonLookups();
        assignment.setActive(false);
        UserRoleHospitalAssignment otherOwnHere = assignmentOf(staff.getUser(), hospital, true);
        when(assignmentRepository.findById(otherOwnHere.getId())).thenReturn(Optional.of(otherOwnHere));

        assertThat(editOwnOrderNaming(otherOwnHere.getId()).getAssignment()).isSameAs(otherOwnHere);
    }

    private void mockCommonLookups() {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        // Lenient: an edit that keeps the ordering clinician never looks the row up.
        lenient().when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        // The caller is the ordering clinician.
        lenient().when(authUtils.resolveUserId(any())).thenReturn(Optional.of(orderingUserId));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(roleValidator.canOrderLabTests(orderingUserId, hospitalId)).thenReturn(true);
        when(labTestDefinitionRepository.findById(labTestDefinitionId)).thenReturn(Optional.of(labTestDefinition));
        // Lenient: an edit that keeps the ordering clinician and the assignment never looks it up.
        lenient().when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
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

    @Test
    void getLabOrdersByPatientIdFollowsThePatientAndAccountsTheReach() {
        // E9 #59b — a lab order placed at Hôpital B is on the list at Hôpital A
        // when the policy lets this caller read B for this patient, and the
        // disclosure is accounted per source hospital.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(otherHospitalId);
        other.setName("Hôpital B");
        LabOrder local = new LabOrder();
        local.setId(UUID.randomUUID());
        local.setHospital(hospital);
        LabOrder foreign = new LabOrder();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(orderingUserId);
        when(recordAccessPolicy.readableHospitalIds(orderingUserId, patientId, hospitalId))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(labOrderRepository.findByPatientIdReadableOrPerformedAt(patientId, Set.of(hospitalId, otherHospitalId), hospitalId))
            .thenReturn(List.of(local, foreign));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class)))
            .thenAnswer(inv -> LabOrderResponseDTO.builder().id(((LabOrder) inv.getArgument(0)).getId().toString()).build());

        List<LabOrderResponseDTO> result = labOrderService.getLabOrdersByPatientId(patientId, Locale.ENGLISH);

        assertThat(result).extracting(LabOrderResponseDTO::getId).containsExactly(local.getId().toString(), foreign.getId().toString());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), eq(orderingUserId), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }
}
