package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderSignatureRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.impl.ImagingOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import java.util.Map;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class ImagingOrderServiceImplTest {

    private static final String BODY_REGION_CHEST = "Chest";

    @Mock
    private ImagingOrderRepository imagingOrderRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private ImagingOrderMapper imagingOrderMapper;
    @Mock
    private com.example.hms.utility.RoleValidator roleValidator;
    @Mock
    private com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private com.example.hms.repository.UserRepository userRepository;
    @Mock
    private com.example.hms.repository.StaffRepository staffRepository;

    private com.example.hms.model.User signer(UUID id, String first, String last) {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(id);
        user.setFirstName(first);
        user.setLastName(last);
        user.setUsername("u-" + id);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    @InjectMocks
    private ImagingOrderServiceImpl imagingOrderService;

    /**
     * The real subject guard. These tests set no authentication, so it waves
     * every read through, as it does for any caller that is not patient-only;
     * the patient cases are in PatientSubjectReadGuardTest and the
     * per-service ownership tests.
     */
    @Spy
    private PatientSubjectReadGuard subjectReadGuard =
        new PatientSubjectReadGuard(mock(ControllerAuthUtils.class), mock(PatientRepository.class));
    @Mock
    private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock
    private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    private UUID patientId;
    private UUID hospitalId;
    private UUID orderId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

        hospital = new Hospital();
        hospital.setId(hospitalId);

        // Sonar S6809: production code injects `self` via Spring's
        // setSelf(@Lazy ImagingOrderService). Mockito constructs the
        // SUT directly so the setter never fires — wire it manually
        // here so getAllOrders(...) → self.getOrdersByHospital(...)
        // does not NPE in the unit test. Pointing self at the SUT
        // mirrors the in-tx behaviour (REQUIRED propagation joins
        // the outer tx, so the call resolves to the same instance).
        imagingOrderService.setSelf(imagingOrderService);
    }

    @Test
    void createOrderSetsOrderingUserAndDuplicateMetadata() {
        UUID orderingUserId = UUID.randomUUID();
        ImagingOrderRequestDTO request = ImagingOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .modality(ImagingModality.CT)
            .studyType("CT Chest with contrast")
            .bodyRegion(BODY_REGION_CHEST)
            .priority(ImagingOrderPriority.ROUTINE)
            .build();

        ImagingOrder order = new ImagingOrder();
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setModality(ImagingModality.CT);
        order.setStudyType("CT Chest with contrast");
        order.setPriority(ImagingOrderPriority.ROUTINE);
        order.setStatus(ImagingOrderStatus.DRAFT);

        ImagingOrder duplicate = new ImagingOrder();
        UUID duplicateId = UUID.randomUUID();
        duplicate.setId(duplicateId);
        duplicate.setOrderedAt(LocalDateTime.now().minusDays(2));
        List<ImagingOrder> duplicates = List.of(duplicate);

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder()
            .id(UUID.randomUUID())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(imagingOrderMapper.toEntity(request, patient, hospital)).thenReturn(order);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), eq(BODY_REGION_CHEST), any(LocalDateTime.class)))
            .thenReturn(duplicates);
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order, duplicates)).thenReturn(responseDTO);

        signer(orderingUserId, "Ada", "Order");
        ImagingOrderResponseDTO result = imagingOrderService.createOrder(request, orderingUserId);
        assertThat(order.getOrderingProviderName()).isEqualTo("Ada Order");

        assertThat(order.getOrderingProviderUserId()).isEqualTo(orderingUserId);
        assertThat(order.getDuplicateOfRecentOrder()).isTrue();
        assertThat(order.getDuplicateReferenceOrderId()).isEqualTo(duplicateId);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void updateOrderStatusCancelledPersistsReasonAndAuditFields() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setStatus(ImagingOrderStatus.ORDERED);

        UUID performerId = UUID.randomUUID();
        ImagingOrderStatusUpdateRequestDTO request = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.CANCELLED)
            .scheduledDate(LocalDate.now().plusDays(1))
            .scheduledTime("13:00")
            .appointmentLocation("Radiology")
            .workflowNotes("Patient requested reschedule")
            .requiresAuthorization(Boolean.TRUE)
            .authorizationNumber("AUTH-123")
            .cancellationReason("Duplicate order")
            .performedByUserId(performerId)
            .performedByName("Dr. Watson")
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        // QA D503 class: the performer is the session, never the request's claim.
        UUID sessionUserId = UUID.randomUUID();
        com.example.hms.model.User sessionUser = signer(sessionUserId, "Ada", "Session");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(sessionUser, null, List.of()));
        ImagingOrderResponseDTO result;
        try {
            result = imagingOrderService.updateOrderStatus(orderId, request);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Duplicate order");
        assertThat(order.getCancelledByUserId()).isEqualTo(sessionUserId).isNotEqualTo(performerId);
        assertThat(order.getCancelledByName()).isEqualTo("Ada Session");
        assertThat(order.getStatusUpdatedAt()).isNotNull();
        assertThat(order.getStatusUpdatedBy()).isEqualTo(sessionUserId);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void previewDuplicatesTrimsBodyRegionBeforeQuery() {
        ImagingOrder existing = new ImagingOrder();
        existing.setId(UUID.randomUUID());
        existing.setBodyRegion(BODY_REGION_CHEST);
        List<ImagingOrder> matches = List.of(existing);

        ImagingOrderDuplicateMatchDTO duplicateDTO = ImagingOrderDuplicateMatchDTO.builder()
            .orderId(existing.getId())
            .build();

        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.MRI), anyString(), any(LocalDateTime.class)))
            .thenReturn(matches);
        when(imagingOrderMapper.toDuplicateMatchDTO(existing)).thenReturn(duplicateDTO);

        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, ImagingModality.MRI, "  Chest  ", 14);

        ArgumentCaptor<String> bodyRegionCaptor = ArgumentCaptor.forClass(String.class);
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(ImagingModality.MRI), bodyRegionCaptor.capture(), any(LocalDateTime.class));

        assertThat(bodyRegionCaptor.getValue()).isEqualTo(BODY_REGION_CHEST);
        assertThat(result).containsExactly(duplicateDTO);
    }

    @Test
    void updateOrderClearsDuplicateFlagsWhenNoMatches() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setModality(ImagingModality.XRAY);
        order.setDuplicateOfRecentOrder(true);
        order.setDuplicateReferenceOrderId(UUID.randomUUID());

        ImagingOrderRequestDTO request = ImagingOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .modality(ImagingModality.XRAY)
            .studyType("XR Knee")
            .priority(ImagingOrderPriority.ROUTINE)
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        doNothing().when(imagingOrderMapper).updateEntityFromRequest(order, request);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order, Collections.emptyList())).thenReturn(responseDTO);

        ImagingOrderResponseDTO result = imagingOrderService.updateOrder(orderId, request, null);

        assertThat(order.getDuplicateOfRecentOrder()).isFalse();
        assertThat(order.getDuplicateReferenceOrderId()).isNull();
        assertThat(result).isSameAs(responseDTO);
    }

    /** QA D970 (sibling): an edited imaging order is not flagged a duplicate of itself. */
    @Test
    void d970_anEditedOrderIsNotADuplicateOfItself() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setModality(ImagingModality.XRAY);
        ImagingOrderRequestDTO request = ImagingOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .modality(ImagingModality.XRAY)
            .studyType("XR Knee")
            .priority(ImagingOrderPriority.ROUTINE)
            .build();
        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        doNothing().when(imagingOrderMapper).updateEntityFromRequest(order, request);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class)))
            .thenReturn(List.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order, Collections.emptyList())).thenReturn(responseDTO);

        imagingOrderService.updateOrder(orderId, request, null);

        assertThat(order.getDuplicateOfRecentOrder()).isFalse();
        assertThat(order.getDuplicateReferenceOrderId()).isNull();
    }

    @Test
    void qaD501_aSignedOrderIsNeverEditedInPlace() {
        UUID signerId = UUID.randomUUID();
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setOrderingProviderUserId(signerId);
        order.setProviderSignedAt(LocalDateTime.now().minusHours(1));
        order.setStudyType("Chest PA");
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        ImagingOrderRequestDTO edit = ImagingOrderRequestDTO.builder().studyType("CT Head").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, edit, signerId))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getStudyType()).isEqualTo("Chest PA");
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void qaD501_anotherUserCannotEditAnUnsignedOrder() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setOrderingProviderUserId(UUID.randomUUID());
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        ImagingOrderRequestDTO edit = ImagingOrderRequestDTO.builder().studyType("CT Head").build();
        UUID nurse = UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, edit, nurse))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void captureProviderSignatureRefusesASecondDoctorOnASignedOrder() {
        // QA D407: DOCTOR2 re-signed DOCTOR's order and became its ordering provider.
        UUID firstSigner = UUID.randomUUID();
        LocalDateTime signedAt = LocalDateTime.now().minusHours(1);
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.ORDERED);
        order.setOrderingProviderUserId(firstSigner);
        order.setOrderingProviderName("First Doctor");
        order.setProviderSignedAt(signedAt);
        UUID secondSigner = UUID.randomUUID();
        signer(secondSigner, "Second", "Doctor");
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        ImagingOrderSignatureRequestDTO request = ImagingOrderSignatureRequestDTO.builder()
            .signatureStatement("Electronically signed").attestationConfirmed(Boolean.TRUE).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> imagingOrderService.captureProviderSignature(orderId, request, secondSigner))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getOrderingProviderUserId()).isEqualTo(firstSigner);
        assertThat(order.getProviderSignedAt()).isEqualTo(signedAt);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void captureProviderSignatureBySameSignerIsANoOp() {
        UUID firstSigner = UUID.randomUUID();
        LocalDateTime signedAt = LocalDateTime.now().minusHours(1);
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.ORDERED);
        order.setOrderingProviderUserId(firstSigner);
        order.setProviderSignedAt(signedAt);
        signer(firstSigner, "First", "Doctor");
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        ImagingOrderResponseDTO result = imagingOrderService.captureProviderSignature(orderId,
            ImagingOrderSignatureRequestDTO.builder().attestationConfirmed(Boolean.TRUE).build(), firstSigner);

        assertThat(result).isSameAs(responseDTO);
        assertThat(order.getProviderSignedAt()).isEqualTo(signedAt);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void captureProviderSignaturePromotesDraftOrder() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.DRAFT);

        LocalDateTime signedAt = LocalDateTime.now().minusMinutes(5);
        UUID signerId = UUID.randomUUID();
        signer(signerId, "Stephen", "Strange");
        ImagingOrderSignatureRequestDTO request = ImagingOrderSignatureRequestDTO.builder()
            .providerName("Dr. Strange")
            .providerNpi("5555")
            .providerUserId(signerId)
            .signatureStatement("Electronically signed")
            .signedAt(signedAt)
            .attestationConfirmed(Boolean.TRUE)
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        ImagingOrderResponseDTO result = imagingOrderService.captureProviderSignature(orderId, request, signerId);

        assertThat(order.getOrderingProviderName()).isEqualTo("Stephen Strange");
        assertThat(order.getOrderingProviderNpi()).isEqualTo("5555");
        assertThat(order.getOrderingProviderUserId()).isEqualTo(request.getProviderUserId());
        assertThat(order.getProviderSignatureStatement()).isEqualTo("Electronically signed");
        // QA D302: a body-supplied time is ignored; the server clock signs.
        assertThat(order.getProviderSignedAt()).isAfter(signedAt);
        assertThat(order.getAttestationConfirmed()).isTrue();
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void getOrderReturnsResponseDTO() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        ImagingOrderResponseDTO result = imagingOrderService.getOrder(orderId);

        assertThat(result.getId()).isEqualTo(orderId);
    }

    @Test
    void getOrderThrowsWhenNotFound() {
        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            ResourceNotFoundException.class,
            () -> imagingOrderService.getOrder(orderId)
        );
    }

    @Test
    void getOrdersByPatientFiltersByStatusWhenProvided() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findByPatient_IdAndStatusOrderByOrderedAtDesc(patientId, ImagingOrderStatus.ORDERED))
            .thenReturn(List.of(order));
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        List<ImagingOrderResponseDTO> result = imagingOrderService.getOrdersByPatient(patientId, ImagingOrderStatus.ORDERED);

        assertThat(result).hasSize(1);
    }

    @Test
    void getOrdersByPatientReturnsAllWhenStatusNull() {
        when(imagingOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId)).thenReturn(List.of());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getOrdersByPatient(patientId, null);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findByPatient_IdOrderByOrderedAtDesc(patientId);
    }

    @Test
    void getOrdersByHospitalFiltersByStatusWhenProvided() {
        when(imagingOrderRepository.findByHospital_IdAndStatusInOrderByOrderedAtDesc(hospitalId, List.of(ImagingOrderStatus.DRAFT)))
            .thenReturn(List.of());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getOrdersByHospital(hospitalId, ImagingOrderStatus.DRAFT);

        assertThat(result).isEmpty();
    }

    @Test
    void getOrdersByHospitalReturnsAllWhenStatusNull() {
        when(imagingOrderRepository.findByHospital_IdOrderByOrderedAtDesc(hospitalId)).thenReturn(List.of());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getOrdersByHospital(hospitalId, null);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findByHospital_IdOrderByOrderedAtDesc(hospitalId);
    }

    @Test
    void getAllOrdersFiltersByStatusWhenProvided() {
        when(imagingOrderRepository.findByStatusOrderByOrderedAtDesc(ImagingOrderStatus.COMPLETED))
            .thenReturn(List.of());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getAllOrders(ImagingOrderStatus.COMPLETED);

        assertThat(result).isEmpty();
    }

    @Test
    void getAllOrdersReturnsAllWhenStatusNull() {
        when(imagingOrderRepository.findAllByOrderByOrderedAtDesc()).thenReturn(List.of());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getAllOrders(null);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findAllByOrderByOrderedAtDesc();
    }

    @Test
    void previewDuplicatesReturnsEmptyWhenPatientIdNull() {
        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(null, ImagingModality.CT, "Chest", 30);

        assertThat(result).isEmpty();
    }

    @Test
    void previewDuplicatesReturnsEmptyWhenModalityNull() {
        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, null, "Chest", 30);

        assertThat(result).isEmpty();
    }

    @Test
    void captureProviderSignatureUsesCurrentTimeWhenSignedAtNull() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.ORDERED);

        ImagingOrderSignatureRequestDTO request = ImagingOrderSignatureRequestDTO.builder()
            .providerName("Dr. House")
            .signatureStatement("Signed")
            .signedAt(null)
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        UUID houseId = UUID.randomUUID();
        signer(houseId, "Greg", "House");
        imagingOrderService.captureProviderSignature(orderId, request, houseId);

        assertThat(order.getProviderSignedAt()).isNotNull();
        assertThat(order.getOrderingProviderUserId()).isEqualTo(houseId);
    }

    @Test
    void createOrderNoDuplicatesClearsDuplicateFlags() {
        ImagingOrderRequestDTO request = ImagingOrderRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .modality(ImagingModality.CT)
            .studyType("CT Head")
            .bodyRegion(null)
            .priority(ImagingOrderPriority.ROUTINE)
            .build();

        ImagingOrder order = new ImagingOrder();
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setModality(ImagingModality.CT);

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(imagingOrderMapper.toEntity(request, patient, hospital)).thenReturn(order);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order, Collections.emptyList())).thenReturn(responseDTO);

        order.setOrderingProviderName("Dr. Somebody Else");
        imagingOrderService.createOrder(request, null);

        // QA D302: a free-text name with no resolved user never poses as the orderer.
        assertThat(order.getOrderingProviderName()).isNull();
        assertThat(order.getDuplicateOfRecentOrder()).isFalse();
        assertThat(order.getDuplicateReferenceOrderId()).isNull();
        assertThat(order.getOrderingProviderUserId()).isNull();
    }

    @Test
    void updateOrderStatusNonCancelledDoesNotSetCancellationFields() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.DRAFT);

        ImagingOrderStatusUpdateRequestDTO request = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.SCHEDULED)
            .scheduledDate(LocalDate.now().plusDays(3))
            .scheduledTime("09:00")
            .appointmentLocation("Wing A")
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        imagingOrderService.updateOrderStatus(orderId, request);

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.SCHEDULED);
        assertThat(order.getCancellationReason()).isNull();
        assertThat(order.getCancelledAt()).isNull();
    }

    @Test
    void normalizeBodyRegionReturnsNullForNull() {
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, ImagingModality.CT, null, 30);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class));
    }

    @Test
    void normalizeBodyRegionReturnsNullForEmptyString() {
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, ImagingModality.CT, "", 30);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class));
    }

    // ── Tenant isolation tests ──

    @Test
    void getOrder_crossHospital_throws404() {
        UUID otherHospitalId = UUID.randomUUID();
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setHospital(hospital);

        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        org.junit.jupiter.api.Assertions.assertThrows(
            ResourceNotFoundException.class,
            () -> imagingOrderService.getOrder(orderId)
        );
    }

    /** QA D508: the order the by-patient list showed hospital B opens by id at B too. */
    @Test
    void d508_getOrder_readableForeignOrder_matchesTheList() {
        UUID hospitalB = UUID.randomUUID();
        UUID doctorB = UUID.randomUUID();
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setPatient(patient);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();
        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roleValidator.getCurrentUserId()).thenReturn(doctorB);
        when(recordAccessPolicy.readableHospitalIds(doctorB, patientId, hospitalB))
            .thenReturn(java.util.Set.of(hospitalB, hospitalId));
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        assertThat(imagingOrderService.getOrder(orderId).getId()).isEqualTo(orderId);
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalB), eq(doctorB), isNull(), any(), anyString());
    }

    /** QA D508: a foreign order the list would not show is a 404 by id as well. */
    @Test
    void d508_getOrder_unreadableForeignOrder_isNotFound() {
        UUID hospitalB = UUID.randomUUID();
        UUID doctorB = UUID.randomUUID();
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setPatient(patient);
        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roleValidator.getCurrentUserId()).thenReturn(doctorB);
        when(recordAccessPolicy.readableHospitalIds(doctorB, patientId, hospitalB)).thenReturn(java.util.Set.of(hospitalB));

        org.junit.jupiter.api.Assertions.assertThrows(ResourceNotFoundException.class,
            () -> imagingOrderService.getOrder(orderId));
    }

    /** QA D652: GET /imaging/orders/patient/{A3} as DOCTOR_B leaves out hospital A's DRAFT, as its by-id read does. */
    @Test
    void d652_patientListAtB_leavesOutHospitalADraft_andTheByIdReadAgrees() {
        UUID hospitalB = UUID.randomUUID();
        UUID doctorB = UUID.randomUUID();
        ImagingOrder draftAtA = new ImagingOrder();
        draftAtA.setId(orderId);
        draftAtA.setHospital(hospital);
        draftAtA.setPatient(patient);
        draftAtA.setStatus(ImagingOrderStatus.DRAFT);
        ImagingOrder orderedAtA = new ImagingOrder();
        orderedAtA.setId(UUID.randomUUID());
        orderedAtA.setHospital(hospital);
        orderedAtA.setPatient(patient);
        orderedAtA.setStatus(ImagingOrderStatus.ORDERED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roleValidator.getCurrentUserId()).thenReturn(doctorB);
        when(recordAccessPolicy.readableHospitalIds(doctorB, patientId, hospitalB))
            .thenReturn(java.util.Set.of(hospitalB, hospitalId));
        when(imagingOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
            patientId, java.util.Set.of(hospitalB, hospitalId))).thenReturn(List.of(draftAtA, orderedAtA));
        when(imagingOrderMapper.toResponseDTO(any(ImagingOrder.class))).thenAnswer(inv ->
            ImagingOrderResponseDTO.builder().id(((ImagingOrder) inv.getArgument(0)).getId()).build());
        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(draftAtA));

        assertThat(imagingOrderService.getOrdersByPatient(patientId, null))
            .extracting(ImagingOrderResponseDTO::getId).containsExactly(orderedAtA.getId());
        org.junit.jupiter.api.Assertions.assertThrows(ResourceNotFoundException.class,
            () -> imagingOrderService.getOrder(orderId));
    }

    /** D508 path re-verified: reading across is not acting across — a status write stays 404. */
    @Test
    void d508_updateStatus_onReadableForeignOrder_staysNotFound() {
        UUID hospitalB = UUID.randomUUID();
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        order.setPatient(patient);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);

        var statusUpdate = new com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO();
        org.junit.jupiter.api.Assertions.assertThrows(ResourceNotFoundException.class,
            () -> imagingOrderService.updateOrderStatus(orderId, statusUpdate));
    }

    @Test
    void getOrder_sameHospital_success() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        assertThat(imagingOrderService.getOrder(orderId).getId()).isEqualTo(orderId);
    }

    @Test
    void getOrder_superAdmin_noFilter() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setHospital(hospital);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        assertThat(imagingOrderService.getOrder(orderId)).isNotNull();
    }

    @Test
    void getOrdersByPatient_followsThePatientAndAccountsTheReach() {
        // E9 #59b — read across the readable set at the database (no more
        // load-everything-then-filter); the foreign row is accounted.
        UUID otherHospId = UUID.randomUUID();
        Hospital otherHosp = new Hospital(); otherHosp.setId(otherHospId);

        ImagingOrder ownOrder = new ImagingOrder(); ownOrder.setId(UUID.randomUUID()); ownOrder.setHospital(hospital);
        ImagingOrder otherOrder = new ImagingOrder(); otherOrder.setId(UUID.randomUUID()); otherOrder.setHospital(otherHosp);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId, otherHospId));
        when(imagingOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, Set.of(hospitalId, otherHospId)))
            .thenReturn(List.of(ownOrder, otherOrder));
        when(imagingOrderMapper.toResponseDTO(any(ImagingOrder.class)))
            .thenAnswer(inv -> ImagingOrderResponseDTO.builder().id(((ImagingOrder) inv.getArgument(0)).getId()).build());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getOrdersByPatient(patientId, null);
        assertThat(result).extracting(ImagingOrderResponseDTO::getId).containsExactly(ownOrder.getId(), otherOrder.getId());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospId.toString(), 1L)), anyString());
    }

    @Test
    void getOrdersByPatient_scopedWithStatusReadsTheReadableSet() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId));
        when(imagingOrderRepository.findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedAtDesc(
                patientId, Set.of(hospitalId), ImagingOrderStatus.ORDERED)).thenReturn(List.of());

        assertThat(imagingOrderService.getOrdersByPatient(patientId, ImagingOrderStatus.ORDERED)).isEmpty();
        verify(imagingOrderRepository).findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedAtDesc(
            patientId, Set.of(hospitalId), ImagingOrderStatus.ORDERED);
    }

    @Test
    void getOrdersByPatient_superAdmin_noFilter() {
        ImagingOrder order = new ImagingOrder(); order.setId(orderId); order.setHospital(hospital);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(imagingOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId)).thenReturn(List.of(order));
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        assertThat(imagingOrderService.getOrdersByPatient(patientId, null)).hasSize(1);
    }

    @Test
    void getAllOrders_scopedToHospital_delegatesToHospitalQuery() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(imagingOrderRepository.findByHospital_IdOrderByOrderedAtDesc(hospitalId)).thenReturn(List.of());

        List<ImagingOrderResponseDTO> result = imagingOrderService.getAllOrders(null);
        assertThat(result).isEmpty();
    }

    @Test
    void getAllOrders_superAdmin_noFilter_withStatus() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(imagingOrderRepository.findByStatusOrderByOrderedAtDesc(ImagingOrderStatus.COMPLETED)).thenReturn(List.of());

        assertThat(imagingOrderService.getAllOrders(ImagingOrderStatus.COMPLETED)).isEmpty();
    }

    // ── Write paths answer to the acting hospital, like getOrder ──

    private ImagingOrder orderAt(Hospital at) {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setPatient(patient);
        order.setHospital(at);
        order.setModality(ImagingModality.XRAY);
        order.setStatus(ImagingOrderStatus.DRAFT);
        return order;
    }

    @Test
    void writesToAnotherHospitalsOrder_answerExactlyAsAMissingOrder() {
        // What the same id answers when no row matches it at all. Compared by
        // message key: the resolved text depends on whichever MessageSource an
        // earlier test left in MessageUtil.
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.empty());
        String missingKey = ((ResourceNotFoundException) org.assertj.core.api.Assertions.catchThrowable(
            () -> imagingOrderService.updateOrderStatus(orderId, new ImagingOrderStatusUpdateRequestDTO())))
            .getMessageKey();
        assertThat(missingKey).isEqualTo("imaging.order.notFound");

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(orderAt(hospital)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        ImagingOrderRequestDTO update = new ImagingOrderRequestDTO();
        ImagingOrderStatusUpdateRequestDTO status = new ImagingOrderStatusUpdateRequestDTO();
        status.setStatus(ImagingOrderStatus.CANCELLED);
        ImagingOrderSignatureRequestDTO signature = new ImagingOrderSignatureRequestDTO();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, update, null))
            .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> assertThat(e.getMessageKey()).isEqualTo(missingKey));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, status))
            .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> assertThat(e.getMessageKey()).isEqualTo(missingKey));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.captureProviderSignature(orderId, signature, UUID.randomUUID()))
            .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> assertThat(e.getMessageKey()).isEqualTo(missingKey));
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void updateOrder_refusesToMoveTheOrderAwayFromTheActingHospital() {
        UUID elsewhereId = UUID.randomUUID();
        Hospital elsewhere = new Hospital();
        elsewhere.setId(elsewhereId);
        ImagingOrder order = orderAt(hospital);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(hospitalRepository.findClinicalById(elsewhereId)).thenReturn(Optional.of(elsewhere));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        ImagingOrderRequestDTO request = new ImagingOrderRequestDTO();
        request.setHospitalId(elsewhereId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, request, null))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(order.getHospital()).isSameAs(hospital);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void createOrder_refusesAHospitalOtherThanTheActingOne() {
        UUID elsewhereId = UUID.randomUUID();
        Hospital elsewhere = new Hospital();
        elsewhere.setId(elsewhereId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(elsewhereId)).thenReturn(Optional.of(elsewhere));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        ImagingOrderRequestDTO request = new ImagingOrderRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(elsewhereId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.createOrder(request, null))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void superAdminInGlobalView_keepsWritingAndMovingAnyOrder() {
        UUID elsewhereId = UUID.randomUUID();
        Hospital elsewhere = new Hospital();
        elsewhere.setId(elsewhereId);
        ImagingOrder order = orderAt(hospital);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(hospitalRepository.findClinicalById(elsewhereId)).thenReturn(Optional.of(elsewhere));
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(imagingOrderRepository.save(order)).thenReturn(order);

        ImagingOrderRequestDTO request = new ImagingOrderRequestDTO();
        request.setHospitalId(elsewhereId);
        imagingOrderService.updateOrder(orderId, request, null);
        assertThat(order.getHospital()).isSameAs(elsewhere);

        ImagingOrderStatusUpdateRequestDTO status = new ImagingOrderStatusUpdateRequestDTO();
        status.setStatus(ImagingOrderStatus.SCHEDULED);
        imagingOrderService.updateOrderStatus(orderId, status);
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.SCHEDULED);

        ImagingOrderSignatureRequestDTO signature = new ImagingOrderSignatureRequestDTO();
        signature.setProviderName("Admin");
        UUID adminId = UUID.randomUUID();
        signer(adminId, "Super", "Admin");
        imagingOrderService.captureProviderSignature(orderId, signature, adminId);
        assertThat(order.getProviderSignedAt()).isNotNull();
    }

    @Test
    void patientRegisteredOnlyElsewhere_answersExactlyAsAMissingPatient() {
        UUID foreignPatientId = UUID.randomUUID();
        Patient foreign = new Patient();
        foreign.setId(foreignPatientId);
        when(patientRepository.findById(foreignPatientId)).thenReturn(Optional.of(foreign));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(foreignPatientId, hospitalId)).thenReturn(false);

        ImagingOrderRequestDTO create = new ImagingOrderRequestDTO();
        create.setPatientId(foreignPatientId);
        create.setHospitalId(hospitalId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.createOrder(create, null))
            .isInstanceOfSatisfying(ResourceNotFoundException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("patient.notFound"));

        ImagingOrder order = orderAt(hospital);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        ImagingOrderRequestDTO repoint = new ImagingOrderRequestDTO();
        repoint.setPatientId(foreignPatientId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, repoint, null))
            .isInstanceOfSatisfying(ResourceNotFoundException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("patient.notFound"));
        assertThat(order.getPatient()).isSameAs(patient);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void patientRegisteredAtTheActingHospital_isAccepted() {
        UUID otherPatientId = UUID.randomUUID();
        Patient other = new Patient();
        other.setId(otherPatientId);
        ImagingOrder order = orderAt(hospital);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(patientRepository.findById(otherPatientId)).thenReturn(Optional.of(other));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(otherPatientId, hospitalId)).thenReturn(true);
        when(imagingOrderRepository.save(order)).thenReturn(order);

        ImagingOrderRequestDTO repoint = new ImagingOrderRequestDTO();
        repoint.setPatientId(otherPatientId);
        imagingOrderService.updateOrder(orderId, repoint, null);
        assertThat(order.getPatient()).isSameAs(other);
    }

    @Test
    void superAdminInGlobalView_isNotHeldToARegistration() {
        UUID anyPatientId = UUID.randomUUID();
        Patient any = new Patient();
        any.setId(anyPatientId);
        ImagingOrder order = orderAt(hospital);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(patientRepository.findById(anyPatientId)).thenReturn(Optional.of(any));
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(imagingOrderRepository.save(order)).thenReturn(order);

        ImagingOrderRequestDTO repoint = new ImagingOrderRequestDTO();
        repoint.setPatientId(anyPatientId);
        imagingOrderService.updateOrder(orderId, repoint, null);
        assertThat(order.getPatient()).isSameAs(any);
        org.mockito.Mockito.verifyNoInteractions(registrationRepository);
    }

    @Test
    void qaD302_signingInAnotherDoctorsNameIsRefused() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.DRAFT);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        UUID doctorA = UUID.randomUUID();
        signer(doctorA, "Doc", "A");

        ImagingOrderSignatureRequestDTO request = ImagingOrderSignatureRequestDTO.builder()
            .providerName("Doctor Two")
            .providerUserId(UUID.randomUUID())
            .signatureStatement("Signed")
            .signedAt(LocalDateTime.of(2026, 1, 1, 8, 0))
            .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> imagingOrderService.captureProviderSignature(orderId, request, doctorA))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(order.getOrderingProviderUserId()).isNull();
        assertThat(order.getProviderSignedAt()).isNull();
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void qaD302_signatureWithoutAResolvedSessionUserIsRefused() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> imagingOrderService.captureProviderSignature(orderId, new ImagingOrderSignatureRequestDTO(), null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
