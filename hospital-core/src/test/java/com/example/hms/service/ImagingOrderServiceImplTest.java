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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        com.example.hms.testsupport.ImagingRolesHeld.whereverTheTokenNamesThem(roleValidator);
        // QA D1920: the duplicate check reads a patient registered where it looks.
        org.mockito.Mockito.lenient()
            .when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
    }

    /** QA D972 (round 18): an imaging order written with no visit joins the open visit, so checkout bills it. */
    @Test
    void anOrderWithNoVisitJoinsThePatientsOpenVisit_D972() {
        com.example.hms.repository.EncounterRepository encounters = mock(com.example.hms.repository.EncounterRepository.class);
        imagingOrderService.setEncounterRepository(encounters);
        com.example.hms.model.Encounter older = new com.example.hms.model.Encounter();
        older.setId(UUID.randomUUID());
        older.setEncounterDate(LocalDateTime.now().minusDays(3));
        com.example.hms.model.Encounter today = new com.example.hms.model.Encounter();
        today.setId(UUID.randomUUID());
        today.setEncounterDate(LocalDateTime.now().minusHours(1));
        when(encounters.findByPatient_IdAndHospital_IdAndStatusNotIn(eq(patientId), eq(hospitalId), any()))
            .thenReturn(List.of(older, today));
        ImagingOrder order = stubCreate(null);

        imagingOrderService.createOrder(requestWithVisit(null), null);

        assertThat(order.getEncounterId()).isEqualTo(today.getId());
    }

    /** QA D972: a visit of another patient is refused; it would put the order on someone else's invoice. */
    @Test
    void aVisitOfAnotherPatientIsRefused_D972() {
        com.example.hms.repository.EncounterRepository encounters = mock(com.example.hms.repository.EncounterRepository.class);
        imagingOrderService.setEncounterRepository(encounters);
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        com.example.hms.model.Encounter theirs = new com.example.hms.model.Encounter();
        theirs.setId(UUID.randomUUID());
        theirs.setPatient(other);
        when(encounters.findByIdAndHospital_Id(theirs.getId(), hospitalId)).thenReturn(Optional.of(theirs));
        stubCreate(theirs.getId());
        ImagingOrderRequestDTO request = requestWithVisit(theirs.getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.createOrder(request, null))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(imagingOrderRepository, never()).save(any());
    }

    private ImagingOrderRequestDTO requestWithVisit(UUID encounterId) {
        return ImagingOrderRequestDTO.builder().patientId(patientId).hospitalId(hospitalId)
            .encounterId(encounterId).modality(ImagingModality.XRAY).studyType("Radiographie thoracique")
            .bodyRegion(BODY_REGION_CHEST).priority(ImagingOrderPriority.ROUTINE).build();
    }

    private ImagingOrder stubCreate(UUID encounterId) {
        ImagingOrder order = new ImagingOrder();
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setModality(ImagingModality.XRAY);
        order.setEncounterId(encounterId);
        org.mockito.Mockito.lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        org.mockito.Mockito.lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        org.mockito.Mockito.lenient().when(imagingOrderMapper.toEntity(any(), eq(patient), eq(hospital))).thenReturn(order);
        org.mockito.Mockito.lenient().when(imagingOrderRepository.save(order)).thenReturn(order);
        return order;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D3450 / D3254: a catalogue exam's modality and body region feed the duplicate check, not what the form displayed")
    void aCatalogueExamFeedsTheDuplicateCheck() {
        UUID examId = UUID.randomUUID();
        // The form displayed an X-ray with no region; the catalogue exam is an ultrasound of the pelvis.
        ImagingOrderRequestDTO request = ImagingOrderRequestDTO.builder().patientId(patientId).hospitalId(hospitalId)
            .modality(ImagingModality.XRAY).studyType("affiché").catalogueItemId(examId)
            .priority(ImagingOrderPriority.ROUTINE).build();
        ImagingOrder order = new ImagingOrder();
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setModality(ImagingModality.XRAY);
        order.setStudyType("affiché");
        com.example.hms.service.imaging.ImagingCatalogueService catalogue =
            mock(com.example.hms.service.imaging.ImagingCatalogueService.class);
        org.mockito.Mockito.doAnswer(call -> {
            ImagingOrder placed = call.getArgument(0);
            placed.setModality(ImagingModality.ULTRASOUND);
            placed.setStudyType("Échographie pelvienne");
            placed.setBodyRegion("Pelvis");
            return null;
        }).when(catalogue).applyTo(order, examId, hospitalId);
        imagingOrderService.setImagingCatalogueService(catalogue);
        ImagingOrder duplicate = new ImagingOrder();
        duplicate.setId(UUID.randomUUID());
        duplicate.setOrderedAt(LocalDateTime.now().minusDays(1));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(imagingOrderMapper.toEntity(request, patient, hospital)).thenReturn(order);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId),
            eq(ImagingModality.ULTRASOUND), eq("Pelvis"), any(LocalDateTime.class))).thenReturn(List.of(duplicate));
        when(imagingOrderRepository.save(order)).thenReturn(order);

        UUID orderingUserId = UUID.randomUUID();
        signer(orderingUserId, "Ada", "Order");
        imagingOrderService.createOrder(request, orderingUserId);

        assertThat(order.getDuplicateOfRecentOrder()).isTrue();
        assertThat(order.getDuplicateReferenceOrderId()).isEqualTo(duplicate.getId());
        verify(imagingOrderRepository, never())
            .findPotentialDuplicates(any(), any(), eq(ImagingModality.XRAY), any(), any());
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
        // The mapper copies the request's body region onto the order; the duplicate rule reads the order.
        order.setBodyRegion(BODY_REGION_CHEST);
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
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.CT), eq(BODY_REGION_CHEST), any(LocalDateTime.class)))
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

        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.MRI), anyString(), any(LocalDateTime.class)))
            .thenReturn(matches);
        when(imagingOrderMapper.toDuplicateMatchDTO(existing)).thenReturn(duplicateDTO);

        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, hospitalId, ImagingModality.MRI, "  Chest  ", 14);

        ArgumentCaptor<String> bodyRegionCaptor = ArgumentCaptor.forClass(String.class);
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.MRI), bodyRegionCaptor.capture(), any(LocalDateTime.class));

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
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class)))
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
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class)))
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
        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(null, hospitalId, ImagingModality.CT, "Chest", 30);

        assertThat(result).isEmpty();
    }

    @Test
    void previewDuplicatesReturnsEmptyWhenModalityNull() {
        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, hospitalId, null, "Chest", 30);

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
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
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

    /** r44: the study's completion is recorded when the order is marked done, and only then. */
    @Test
    void updateOrderStatusToCompletedRecordsTheCompletionTime_r44() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.IN_PROGRESS);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());

        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.SCHEDULED).build());
        assertThat(order.getCompletedAt()).isNull();
        // QA D1925: a study is completed once it has been started again.
        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.IN_PROGRESS).build());
        assertThat(order.getCompletedAt()).isNull();

        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.COMPLETED).build());
        assertThat(order.getCompletedAt()).isNotNull().isEqualTo(order.getStatusUpdatedAt());
    }

    /** QA D1098 (round 19): a resulted order is closed to a cancel and to a step back. */
    @Test
    void updateOrderStatus_refusesToCancelOrResetAResultedOrder_D1098() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        for (ImagingOrderStatus target : new ImagingOrderStatus[] {
                ImagingOrderStatus.CANCELLED, ImagingOrderStatus.ORDERED, ImagingOrderStatus.COMPLETED}) {
            ImagingOrderStatusUpdateRequestDTO request = ImagingOrderStatusUpdateRequestDTO.builder()
                .status(target).cancellationReason("Duplicate").build();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> imagingOrderService.updateOrderStatus(orderId, request))
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .extracting(e -> ((com.example.hms.exception.BusinessException) e).getMessageKey())
                .isEqualTo("order.lifecycle.resultedLocked");
        }
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.RESULTS_AVAILABLE);
        verify(imagingOrderRepository, never()).save(any());
    }

    /** QA D1925 (r53): results come from a signed report, never from a status write (was allowed by D1098). */
    @Test
    void updateOrderStatus_aCompletedOrderReachesResultsOnlyBySigning_D1925() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.COMPLETED);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        ImagingOrderStatusUpdateRequestDTO request = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.RESULTS_AVAILABLE).build();
        assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, request))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .extracting(e -> ((com.example.hms.exception.BusinessException) e).getMessageKey())
            .isEqualTo("imaging.order.resultsOnlyBySigning");

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.COMPLETED);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void updateOrderStatus_refusesACancelWithoutAReason_D1098() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.ORDERED);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        for (String blank : new String[] {null, "", "   "}) {
            ImagingOrderStatusUpdateRequestDTO request = ImagingOrderStatusUpdateRequestDTO.builder()
                .status(ImagingOrderStatus.CANCELLED).cancellationReason(blank).build();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> imagingOrderService.updateOrderStatus(orderId, request))
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .extracting(e -> ((com.example.hms.exception.BusinessException) e).getMessageKey())
                .isEqualTo("order.lifecycle.cancelReasonRequired");
        }
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
    }

    @Test
    void normalizeBodyRegionReturnsNullForNull() {
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, hospitalId, ImagingModality.CT, null, 30);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class));
    }

    @Test
    void normalizeBodyRegionReturnsNullForEmptyString() {
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        List<ImagingOrderDuplicateMatchDTO> result = imagingOrderService.previewDuplicates(patientId, hospitalId, ImagingModality.CT, "", 30);

        assertThat(result).isEmpty();
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class));
    }

    // ── QA D1920: the duplicate preview is the caller's hospital, bounded ──

    @Test
    void previewDuplicatesReadsOnlyTheCallersHospital_D1920() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        imagingOrderService.previewDuplicates(patientId, null, ImagingModality.XRAY, null, 30);

        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class));
    }

    @Test
    void previewDuplicatesForAPatientNotRegisteredHereAnswersLikeMissing_D1920() {
        UUID foreignPatient = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(foreignPatient, hospitalId)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                imagingOrderService.previewDuplicates(foreignPatient, null, ImagingModality.XRAY, null, 30))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(imagingOrderRepository, never()).findPotentialDuplicates(any(), any(), any(), any(), any());
    }

    @Test
    void previewDuplicatesNamingAnotherHospitalAnswersLikeMissing_D1920() {
        UUID otherHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                imagingOrderService.previewDuplicates(patientId, otherHospital, ImagingModality.XRAY, null, 30))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(imagingOrderRepository, never()).findPotentialDuplicates(any(), any(), any(), any(), any());
    }

    @Test
    void previewDuplicatesInGlobalViewWithNoHospitalListsNothing_D1920() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThat(imagingOrderService.previewDuplicates(patientId, null, ImagingModality.XRAY, null, 30)).isEmpty();
        verify(imagingOrderRepository, never()).findPotentialDuplicates(any(), any(), any(), any(), any());
    }

    @Test
    void previewDuplicatesLookbackIsCappedServerSide_D1920() {
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.XRAY), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        LocalDateTime before = LocalDateTime.now();
        imagingOrderService.previewDuplicates(patientId, hospitalId, ImagingModality.XRAY, null, 3650);

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(imagingOrderRepository).findPotentialDuplicates(eq(patientId), eq(hospitalId), eq(ImagingModality.XRAY), isNull(), since.capture());
        assertThat(since.getValue()).isAfterOrEqualTo(before.minusDays(90).minusMinutes(1));
    }

    @Test
    void duplicateWindowKeepsTheDefaultAndCapsTheRest_D1920() {
        assertThat(ImagingOrderServiceImpl.duplicateWindowDays(null)).isEqualTo(30);
        assertThat(ImagingOrderServiceImpl.duplicateWindowDays(0)).isEqualTo(30);
        assertThat(ImagingOrderServiceImpl.duplicateWindowDays(-5)).isEqualTo(30);
        assertThat(ImagingOrderServiceImpl.duplicateWindowDays(14)).isEqualTo(14);
        assertThat(ImagingOrderServiceImpl.duplicateWindowDays(365)).isEqualTo(90);
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

    // ═══════════════ QA D930 class (round 16 siblings): cancel and sign ═══════════════

    private ImagingOrderResponseDTO cancelAs(UUID sessionUserId, ImagingOrder order) {
        com.example.hms.model.User sessionUser = new com.example.hms.model.User();
        sessionUser.setId(sessionUserId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(sessionUser, null, List.of()));
        try {
            return imagingOrderService.updateOrderStatus(order.getId(), ImagingOrderStatusUpdateRequestDTO.builder()
                .status(ImagingOrderStatus.CANCELLED).cancellationReason("Not needed").build());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void d930_anotherDoctorCannotCancelAColleaguesOrder() {
        ImagingOrder order = orderAt(hospital);
        order.setStatus(ImagingOrderStatus.ORDERED);
        order.setOrderingProviderUserId(UUID.randomUUID());
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        UUID colleague = UUID.randomUUID();
        when(roleValidator.hasRole(colleague, hospitalId, "RADIOLOGIST")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cancelAs(colleague, order))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void d930_theOrderingProviderCancelsHerOrder() {
        ImagingOrder order = orderAt(hospital);
        order.setStatus(ImagingOrderStatus.ORDERED);
        UUID provider = UUID.randomUUID();
        order.setOrderingProviderUserId(provider);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);

        cancelAs(provider, order);

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
    }

    /** Declared exception: the imaging department (a radiologist of the hospital) calls an exam off. */
    @Test
    void d930_aRadiologistCancelsAnOrderSheDidNotPlace() {
        ImagingOrder order = orderAt(hospital);
        order.setStatus(ImagingOrderStatus.ORDERED);
        order.setOrderingProviderUserId(UUID.randomUUID());
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        UUID radiologist = UUID.randomUUID();
        when(roleValidator.hasRole(radiologist, hospitalId, "RADIOLOGIST")).thenReturn(true);

        cancelAs(radiologist, order);

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
    }

    @Test
    void d930_anotherDoctorCannotSignAColleaguesUnsignedOrder() {
        ImagingOrder order = orderAt(hospital);
        UUID author = UUID.randomUUID();
        order.setOrderingProviderUserId(author);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        UUID colleague = UUID.randomUUID();
        signer(colleague, "Doc", "Two");
        when(roleValidator.draftsForPhysicianSignature(author, hospitalId)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.captureProviderSignature(
                orderId, ImagingOrderSignatureRequestDTO.builder().signatureStatement("Signed").build(), colleague))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getOrderingProviderUserId()).isEqualTo(author);
        assertThat(order.getProviderSignedAt()).isNull();
        verify(imagingOrderRepository, never()).save(any());
    }

    /** Declared exception: a nurse's or midwife's draft is signed by a physician of the hospital (QA D934 rule). */
    @Test
    void d930_aDoctorOfTheHospitalSignsANursesDraftOrder() {
        ImagingOrder order = orderAt(hospital);
        UUID nurse = UUID.randomUUID();
        order.setOrderingProviderUserId(nurse);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        UUID doctor = UUID.randomUUID();
        signer(doctor, "Doc", "One");
        when(roleValidator.draftsForPhysicianSignature(nurse, hospitalId)).thenReturn(true);
        when(roleValidator.isSigningPhysician(doctor, hospitalId)).thenReturn(true);

        imagingOrderService.captureProviderSignature(
            orderId, ImagingOrderSignatureRequestDTO.builder().signatureStatement("Signed").build(), doctor);

        assertThat(order.getProviderSignedAt()).isNotNull();
        assertThat(order.getOrderingProviderUserId()).isEqualTo(doctor);
    }

    // ═══ Review of #976 (QA D930 class): the cancel stamp and the way back out ═══

    private ImagingOrderResponseDTO statusAs(UUID sessionUserId, ImagingOrder order, ImagingOrderStatus status) {
        com.example.hms.model.User sessionUser = new com.example.hms.model.User();
        sessionUser.setId(sessionUserId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(sessionUser, null, List.of()));
        try {
            return imagingOrderService.updateOrderStatus(order.getId(), ImagingOrderStatusUpdateRequestDTO.builder()
                .status(status).cancellationReason("Rewritten reason").build());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static final LocalDateTime ORIGINAL_CANCELLED_AT = LocalDateTime.of(2026, 1, 5, 9, 30);

    private ImagingOrder cancelledBy(UUID author) {
        ImagingOrder order = orderAt(hospital);
        order.setOrderingProviderUserId(author);
        order.setStatus(ImagingOrderStatus.CANCELLED);
        order.setCancellationReason("Original reason");
        order.setCancelledAt(ORIGINAL_CANCELLED_AT);
        order.setCancelledByUserId(author);
        order.setCancelledByName("Original Author");
        return order;
    }

    /*
     * QA D1066 (round 18): the four review-976 tests below used to let the
     * ordering provider or a radiologist reopen a cancelled order, and a
     * colleague re-send CANCELLED. Product decision (round-18 brief): a
     * cancelled order is closed and nobody revives it - the clinician places a
     * new one. Each now asserts the refusal, and that nothing was written.
     */
    @Test
    void d1066_reCancellingACancelledOrderIsRefusedAndKeepsTheOriginalStamp() {
        UUID author = UUID.randomUUID();
        ImagingOrder order = cancelledBy(author);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> statusAs(UUID.randomUUID(), order, ImagingOrderStatus.CANCELLED))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessage("imaging.order.cancelled.locked");

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Original reason");
        assertThat(order.getCancelledAt()).isEqualTo(ORIGINAL_CANCELLED_AT);
        assertThat(order.getCancelledByUserId()).isEqualTo(author);
        assertThat(order.getCancelledByName()).isEqualTo("Original Author");
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void d1066_aColleagueCannotReopenTheAuthorsCancelledOrder() {
        ImagingOrder order = cancelledBy(UUID.randomUUID());
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> statusAs(UUID.randomUUID(), order, ImagingOrderStatus.ORDERED))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessage("imaging.order.cancelled.locked");
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void d1066_theOrderingProviderCannotReopenHerCancelledOrder() {
        UUID author = UUID.randomUUID();
        ImagingOrder order = cancelledBy(author);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        for (ImagingOrderStatus revived : List.of(ImagingOrderStatus.ORDERED, ImagingOrderStatus.SCHEDULED)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statusAs(author, order, revived))
                .as(revived.name())
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .hasMessage("imaging.order.cancelled.locked");
        }
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void d1066_aRadiologistCannotReopenACancelledOrderEither() {
        ImagingOrder order = cancelledBy(UUID.randomUUID());
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        UUID radiologist = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(roleValidator.hasRole(radiologist, hospitalId, "RADIOLOGIST")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> statusAs(radiologist, order, ImagingOrderStatus.ORDERED))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
    }

    /** QA D1428 class: a study done is what was done. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ImagingOrderStatus.class, names = {"COMPLETED", "RESULTS_AVAILABLE"})
    void qaD1428_aResultedOrderIsNotEdited(ImagingOrderStatus resulted) {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(resulted);
        order.setStudyType("Chest PA");
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        ImagingOrderRequestDTO edit = ImagingOrderRequestDTO.builder().studyType("CT Head").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, edit, null))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessage("imaging.order.resulted.locked");
        assertThat(order.getStudyType()).isEqualTo("Chest PA");
        verify(imagingOrderRepository, never()).save(any());
    }

    /**
     * QA D1822 (r51): a status write touches the status, its timestamps and
     * its reason, nothing else. Start, complete and cancel each erased the
     * schedule, the appointment location, the workflow notes and the
     * pre-authorisation. Every other field of the order is read by reflection,
     * so a field added later is covered too.
     */
    @Test
    void aStatusWriteKeepsEveryNonStatusField_D1822() throws Exception {
        Set<String> statusFields = Set.of("status", "statusUpdatedAt", "statusUpdatedBy", "completedAt",
            "cancelledAt", "cancellationReason", "cancelledByUserId", "cancelledByName",
            // Provisional rule 1: the pre-authorisation flag follows the status (ImagingPreAuthorisationPolicy).
            "authorizationPendingSince");
        ImagingOrderStatus[][] steps = {
            {ImagingOrderStatus.ORDERED, ImagingOrderStatus.IN_PROGRESS},
            {ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.COMPLETED},
            {ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.CANCELLED},
        };
        for (ImagingOrderStatus[] step : steps) {
            ImagingOrder order = new ImagingOrder();
            order.setPatient(patient);
            order.setHospital(hospital);
            Map<String, Object> before = new java.util.HashMap<>();
            for (java.lang.reflect.Field f : statusWriteFields(ImagingOrder.class)) {
                if (statusFields.contains(f.getName()) || f.getName().equals("orderingProviderUserId")) {
                    continue;
                }
                Object value = sampleFor(f.getType());
                if (value != null) {
                    f.set(order, value);
                }
                before.put(f.getName(), f.get(order));
            }
            order.setId(orderId);
            before.put("id", orderId);
            order.setScheduledDate(LocalDate.now().minusDays(1));
            before.put("scheduledDate", order.getScheduledDate());
            order.setStatus(step[0]);
            when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(imagingOrderRepository.save(order)).thenReturn(order);
            when(imagingOrderMapper.toResponseDTO(order)).thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());

            imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
                .status(step[1]).cancellationReason("Examen non nécessaire").build());

            assertThat(order.getStatus()).isEqualTo(step[1]);
            for (java.lang.reflect.Field f : statusWriteFields(ImagingOrder.class)) {
                if (before.containsKey(f.getName())) {
                    assertThat(f.get(order)).as("%s -> %s: ImagingOrder.%s", step[0], step[1], f.getName())
                        .isEqualTo(before.get(f.getName()));
                }
            }
        }
    }

    /** QA D1822: a scheduling field the status write does carry is still applied. */
    @Test
    void aStatusWriteStillAppliesTheSchedulingFieldsItCarries_D1822() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.ORDERED);
        order.setAppointmentLocation("Salle 2");
        order.setAuthorizationNumber("PA-1");
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());

        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.SCHEDULED).scheduledDate(LocalDate.now().plusDays(2)).scheduledTime("10:15")
            .build());

        assertThat(order.getScheduledDate()).isEqualTo(LocalDate.now().plusDays(2));
        assertThat(order.getScheduledTime()).isEqualTo("10:15");
        assertThat(order.getAppointmentLocation()).isEqualTo("Salle 2");
        assertThat(order.getAuthorizationNumber()).isEqualTo("PA-1");
    }

    private static List<java.lang.reflect.Field> statusWriteFields(Class<?> type) {
        List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
        }
        return fields;
    }

    /** A stored value per simple type; associations and collections keep what the entity has. */
    private static Object sampleFor(Class<?> type) {
        if (type == String.class) {
            return "stored value";
        }
        if (type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == LocalDate.class) {
            return LocalDate.now().plusDays(7);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.of(2026, 11, 14, 10, 15);
        }
        if (type == UUID.class) {
            return UUID.fromString("00000000-0000-0000-0000-000000001822");
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        return null;
    }

    // ── User decision 2026-11-14: administrators read their hospitals, read-only ──

    private static void signIn(String... authorities) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("u", "n/a",
                java.util.Arrays.stream(authorities)
                    .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
    }

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private ImagingOrder orderAtHospital(UUID id, Hospital at) {
        ImagingOrder order = new ImagingOrder();
        order.setId(id);
        order.setHospital(at);
        order.setPatient(patient);
        return order;
    }

    @Test
    void adminDecision_aHospitalAdminOfANeverReadsBsOrderById_whereADoctorFollowsThePatient() {
        UUID hospitalB = UUID.randomUUID();
        ImagingOrder orderAtA = orderAtHospital(orderId, hospital);
        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(orderAtA));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        lenient().when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalB)))
            .thenReturn(Set.of(hospitalB, hospitalId));
        lenient().when(imagingOrderMapper.toResponseDTO(orderAtA))
            .thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());

        signIn("ROLE_HOSPITAL_ADMIN");
        org.junit.jupiter.api.Assertions.assertThrows(ResourceNotFoundException.class,
            () -> imagingOrderService.getOrder(orderId));

        signIn("ROLE_DOCTOR");
        assertThat(imagingOrderService.getOrder(orderId).getId()).isEqualTo(orderId);
    }

    @Test
    void adminDecision_aHospitalAdminListsTheActingHospitalOnly_neverAnother() {
        UUID hospitalB = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        signIn("ROLE_HOSPITAL_ADMIN");

        assertThatThrownBy(() -> imagingOrderService.getOrdersByHospital(hospitalB, null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(imagingOrderRepository, never()).findByHospital_IdOrderByOrderedAtDesc(hospitalB);
    }

    @Test
    void adminDecision_aHospitalAdminReadsAPatientsOrdersAtTheActingHospitalOnly() {
        UUID hospitalB = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        lenient().when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, hospitalB));
        signIn("ROLE_HOSPITAL_ADMIN");

        imagingOrderService.getOrdersByPatient(patientId, null);

        verify(imagingOrderRepository).findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, Set.of(hospitalId));
    }

    @Test
    void adminDecision_theSuperAdminsCombinedViewHoldsEveryHospital_andTheFilterOne() {
        Hospital hospitalB = new Hospital();
        hospitalB.setId(UUID.randomUUID());
        ImagingOrder atA = orderAtHospital(UUID.randomUUID(), hospital);
        ImagingOrder atB = orderAtHospital(UUID.randomUUID(), hospitalB);
        when(imagingOrderRepository.findAllByOrderByOrderedAtDesc()).thenReturn(List.of(atA, atB));
        when(imagingOrderRepository.findByHospital_IdOrderByOrderedAtDesc(hospitalB.getId())).thenReturn(List.of(atB));
        when(imagingOrderMapper.toResponseDTO(any(ImagingOrder.class))).thenAnswer(inv -> {
            ImagingOrder o = inv.getArgument(0);
            return ImagingOrderResponseDTO.builder().id(o.getId()).hospitalId(o.getHospital().getId()).build();
        });
        signIn("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_PATIENT");

        // « Tous les hôpitaux »: global view, no acting hospital.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        assertThat(imagingOrderService.getAllOrders(null)).extracting(ImagingOrderResponseDTO::getHospitalId)
            .containsExactly(hospitalId, hospitalB.getId());

        // The filter: the interceptor narrows the scope to the named hospital.
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB.getId());
        assertThat(imagingOrderService.getOrdersByHospital(hospitalB.getId(), null))
            .extracting(ImagingOrderResponseDTO::getHospitalId).containsExactly(hospitalB.getId());
    }
    // ═══ QA D1925 (r53): the status writes the endpoint accepts ═══

    /**
     * Guard: every (current, requested) pair, written through the service.
     * The expected table is spelled out here, not read from production, so a
     * loosened table fails as surely as a removed check. RESULTS_AVAILABLE is
     * never a status write's target (signing a report is), COMPLETED follows
     * IN_PROGRESS only, and a resulted or cancelled order is closed.
     */
    @Test
    void everyStatusWritePair_followsTheTransitionTable_D1925() {
        Map<ImagingOrderStatus, Set<ImagingOrderStatus>> expected = new java.util.EnumMap<>(ImagingOrderStatus.class);
        expected.put(ImagingOrderStatus.DRAFT, Set.of(ImagingOrderStatus.DRAFT, ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.CANCELLED));
        expected.put(ImagingOrderStatus.ORDERED, Set.of(ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.IN_PROGRESS,
            ImagingOrderStatus.CANCELLED));
        expected.put(ImagingOrderStatus.PENDING_AUTHORIZATION, Set.of(ImagingOrderStatus.PENDING_AUTHORIZATION,
            ImagingOrderStatus.ORDERED, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.IN_PROGRESS,
            ImagingOrderStatus.CANCELLED));
        expected.put(ImagingOrderStatus.SCHEDULED, Set.of(ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.CANCELLED));
        expected.put(ImagingOrderStatus.IN_PROGRESS, Set.of(ImagingOrderStatus.IN_PROGRESS,
            ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.COMPLETED, ImagingOrderStatus.CANCELLED));
        expected.put(ImagingOrderStatus.COMPLETED, Set.of());
        expected.put(ImagingOrderStatus.RESULTS_AVAILABLE, Set.of());
        expected.put(ImagingOrderStatus.CANCELLED, Set.of());

        lenient().when(imagingOrderRepository.save(any(ImagingOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(imagingOrderMapper.toResponseDTO(any(ImagingOrder.class)))
            .thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (ImagingOrderStatus from : ImagingOrderStatus.values()) {
            for (ImagingOrderStatus to : ImagingOrderStatus.values()) {
                ImagingOrder order = new ImagingOrder();
                order.setId(orderId);
                order.setPatient(patient);
                order.setHospital(hospital);
                order.setStatus(from);
                when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
                Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                    imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
                        .status(to).cancellationReason("Examen non nécessaire").build()));
                if (refused != null && !(refused instanceof com.example.hms.exception.BusinessException)) {
                    throw new AssertionError(from + " -> " + to + " failed with " + refused, refused);
                }
                boolean accepted = refused == null;
                if (accepted != expected.get(from).contains(to)) {
                    wrong.add(from + " -> " + to + (accepted ? " accepted" : " refused"));
                }
            }
        }
        assertThat(wrong).as("status writes off the table").isEmpty();
    }

    /** QA D1925: the r53 jumps are refused with a reason the reader understands. */
    @Test
    void theR53JumpsAreRefusedWithTheirOwnReason_D1925() {
        assertThat(refusalKey(ImagingOrderStatus.ORDERED, ImagingOrderStatus.RESULTS_AVAILABLE, null, null))
            .isEqualTo("imaging.order.resultsOnlyBySigning");
        assertThat(refusalKey(ImagingOrderStatus.ORDERED, ImagingOrderStatus.COMPLETED, null, null))
            .isEqualTo("imaging.order.completeBeforeStart");
        assertThat(refusalKey(ImagingOrderStatus.DRAFT, ImagingOrderStatus.IN_PROGRESS, null, null))
            .isEqualTo("imaging.order.invalidTransition");
    }

    /** QA D1925: a study needing pre-authorisation starts once its number is recorded. */
    @Test
    void aStudyNeedingPreAuthorisationStartsOnlyWithItsNumber_D1925() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.ORDERED);
        order.setRequiresAuthorization(true);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        for (String missing : new String[] {null, "  "}) {
            order.setAuthorizationNumber(missing);
            Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
                    .status(ImagingOrderStatus.IN_PROGRESS).build()));
            assertThat(refused).isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, e -> {
                assertThat(e.getMessageKey()).isEqualTo("imaging.order.authorizationNumberRequired");
                assertThat(e.getField()).isEqualTo("authorizationNumber");
            });
        }
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        verify(imagingOrderRepository, never()).save(any());

        // The number written with the start is enough; so is one already on the order.
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());
        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.IN_PROGRESS).authorizationNumber("PA-R53-01").build());
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
        assertThat(order.getAuthorizationNumber()).isEqualTo("PA-R53-01");
    }

    private String refusalKey(ImagingOrderStatus from, ImagingOrderStatus to, Boolean requiresAuth, String number) {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(from);
        order.setRequiresAuthorization(requiresAuth);
        order.setAuthorizationNumber(number);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() ->
            imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder().status(to).build()));
        assertThat(refused).isInstanceOf(com.example.hms.exception.BusinessException.class);
        return ((com.example.hms.exception.BusinessException) refused).getMessageKey();
    }
    // ── Provisional rule 1 (2026-11-15): urgent imaging is never blocked by a pre-authorisation ──

    private ImagingOrder awaitingAuthorisation(ImagingOrderPriority priority, ImagingOrderStatus status) {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setPriority(priority);
        order.setStatus(status);
        order.setRequiresAuthorization(true);
        lenient().when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        lenient().when(imagingOrderRepository.save(order)).thenReturn(order);
        lenient().when(imagingOrderMapper.toResponseDTO(order)).thenReturn(ImagingOrderResponseDTO.builder().id(orderId).build());
        return order;
    }

    private com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp followUpMock() {
        com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp followUp =
            mock(com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp.class);
        imagingOrderService.setPreAuthorisationFollowUp(followUp);
        return followUp;
    }

    @Test
    void anUrgentOrStatStudyStartsWithoutItsNumber_flaggedAndFollowedUp_rule1() {
        for (ImagingOrderPriority urgent : List.of(ImagingOrderPriority.URGENT, ImagingOrderPriority.STAT)) {
            com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp followUp = followUpMock();
            ImagingOrder order = awaitingAuthorisation(urgent, ImagingOrderStatus.ORDERED);

            imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
                .status(ImagingOrderStatus.IN_PROGRESS).build());

            assertThat(order.getStatus()).as(urgent.name()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
            assertThat(order.getAuthorizationPendingSince()).as(urgent.name()).isNotNull();
            verify(followUp).apply(order, com.example.hms.service.support.ImagingPreAuthorisationPolicy.FollowUp.OPENED);
        }
    }

    @Test
    void aRoutineStudyIsStillRefusedWithoutItsNumber_rule1() {
        com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp followUp = followUpMock();
        ImagingOrder order = awaitingAuthorisation(ImagingOrderPriority.ROUTINE, ImagingOrderStatus.SCHEDULED);

        assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
                .status(ImagingOrderStatus.IN_PROGRESS).build()))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("imaging.order.authorizationNumberRequired"));
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.SCHEDULED);
        assertThat(order.getAuthorizationPendingSince()).isNull();
        verify(imagingOrderRepository, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(followUp);
    }

    @Test
    void recordingTheNumberClearsTheFlagAndClosesTheTask_atEveryStatusTheStudyReached_rule1() {
        for (ImagingOrderStatus reached : List.of(ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.COMPLETED,
                ImagingOrderStatus.RESULTS_AVAILABLE, ImagingOrderStatus.CANCELLED)) {
            com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp followUp = followUpMock();
            ImagingOrder order = awaitingAuthorisation(ImagingOrderPriority.STAT, reached);
            order.setAuthorizationPendingSince(LocalDateTime.now().minusHours(2));

            imagingOrderService.recordAuthorization(orderId,
                com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO.builder()
                    .authorizationNumber("  CNAMU-2026-001 ").build());

            assertThat(order.getAuthorizationNumber()).as(reached.name()).isEqualTo("CNAMU-2026-001");
            assertThat(order.getAuthorizationPendingSince()).as(reached.name()).isNull();
            assertThat(order.getStatus()).as(reached.name()).isEqualTo(reached);
            verify(followUp).apply(order, com.example.hms.service.support.ImagingPreAuthorisationPolicy.FollowUp.CLOSED);
        }
    }

    @Test
    void theNumberSentWithAStatusWriteAlsoClearsTheFlag_rule1() {
        com.example.hms.service.imaging.ImagingPreAuthorisationFollowUp followUp = followUpMock();
        ImagingOrder order = awaitingAuthorisation(ImagingOrderPriority.URGENT, ImagingOrderStatus.IN_PROGRESS);
        order.setAuthorizationPendingSince(LocalDateTime.now().minusHours(1));

        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.COMPLETED).authorizationNumber("PA-77").build());

        assertThat(order.getAuthorizationPendingSince()).isNull();
        verify(followUp).apply(order, com.example.hms.service.support.ImagingPreAuthorisationPolicy.FollowUp.CLOSED);
    }

    @Test
    void aBlankNumberIsRefusedAndAClosedOrderStaysClosed_rule1() {
        ImagingOrder order = awaitingAuthorisation(ImagingOrderPriority.STAT, ImagingOrderStatus.IN_PROGRESS);
        assertThatThrownBy(() -> imagingOrderService.recordAuthorization(orderId,
                com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO.builder().authorizationNumber(" ").build()))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("imaging.order.authorizationNumberBlank"));

        order.setStatus(ImagingOrderStatus.CANCELLED);
        assertThatThrownBy(() -> imagingOrderService.recordAuthorization(orderId,
                com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO.builder().authorizationNumber("PA-1").build()))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        verify(imagingOrderRepository, never()).save(any());
    }

    // ── Provisional rule 2 (2026-11-15): « Commander quand même » records a reason ──

    private List<ImagingOrder> stubDuplicate() {
        ImagingOrder earlier = new ImagingOrder();
        earlier.setId(UUID.randomUUID());
        List<ImagingOrder> matches = List.of(earlier);
        lenient().when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), any(), any(),
            any(LocalDateTime.class))).thenReturn(matches);
        return matches;
    }

    private ImagingOrderRequestDTO overriding(com.example.hms.enums.ImagingDuplicateOverrideReason reason, String note) {
        ImagingOrderRequestDTO request = requestWithVisit(null);
        request.setDuplicateOverride(true);
        request.setDuplicateOverrideReason(reason);
        request.setDuplicateOverrideNote(note);
        return request;
    }

    @Test
    void eachOverrideReasonIsStoredWithWhoAndWhen_rule2() {
        for (com.example.hms.enums.ImagingDuplicateOverrideReason reason
                : com.example.hms.enums.ImagingDuplicateOverrideReason.values()) {
            com.example.hms.service.imaging.ImagingDuplicateOverride audit =
                mock(com.example.hms.service.imaging.ImagingDuplicateOverride.class);
            imagingOrderService.setDuplicateOverride(audit);
            ImagingOrder order = stubCreate(null);
            List<ImagingOrder> matches = stubDuplicate();
            UUID orderer = UUID.randomUUID();
            signer(orderer, "Awa", "Traoré");
            String note = reason == com.example.hms.enums.ImagingDuplicateOverrideReason.OTHER ? "  Bilan préopératoire  " : null;

            imagingOrderService.createOrder(overriding(reason, note), orderer);

            assertThat(order.getDuplicateOfRecentOrder()).as(reason.name()).isTrue();
            assertThat(order.getDuplicateOverride()).as(reason.name()).isTrue();
            assertThat(order.getDuplicateOverrideReason()).as(reason.name()).isEqualTo(reason);
            assertThat(order.getDuplicateOverrideNote()).as(reason.name())
                .isEqualTo(note == null ? null : "Bilan préopératoire");
            assertThat(order.getDuplicateOverrideByUserId()).as(reason.name()).isEqualTo(orderer);
            assertThat(order.getDuplicateOverrideByName()).as(reason.name()).isEqualTo("Awa Traoré");
            assertThat(order.getDuplicateOverrideAt()).as(reason.name()).isNotNull();
            verify(audit).audit(order, matches);
        }
    }

    @Test
    void otherNeedsANote_rule2() {
        stubCreate(null);
        stubDuplicate();
        for (String blank : new String[] {null, "   "}) {
            assertThatThrownBy(() -> imagingOrderService.createOrder(
                    overriding(com.example.hms.enums.ImagingDuplicateOverrideReason.OTHER, blank), null))
                .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("imaging.order.duplicateOverride.noteRequired");
                    assertThat(e.getField()).isEqualTo("duplicateOverrideNote");
                });
        }
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void aClaimedOverrideWithNoReasonIsRefused_rule2() {
        stubCreate(null);
        stubDuplicate();
        assertThatThrownBy(() -> imagingOrderService.createOrder(overriding(null, "note"), null))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, e -> {
                assertThat(e.getMessageKey()).isEqualTo("imaging.order.duplicateOverride.reasonRequired");
                assertThat(e.getField()).isEqualTo("duplicateOverrideReason");
            });
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void noOverrideIsStoredWhenTheServerFindsNoDuplicate_rule2() {
        ImagingOrder order = stubCreate(null);
        lenient().when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(hospitalId), any(), any(),
            any(LocalDateTime.class))).thenReturn(List.of());

        imagingOrderService.createOrder(overriding(
            com.example.hms.enums.ImagingDuplicateOverrideReason.CLINICAL_CHANGE, null), null);

        assertThat(order.getDuplicateOfRecentOrder()).isFalse();
        assertThat(order.getDuplicateOverride()).isNull();
        assertThat(order.getDuplicateOverrideReason()).isNull();
    }

    // ═══════════════ Policy rule 3 (2026-11-20, provisional): the imaging technician ═══════════════

    private static final String TECHNICIAN = "ROLE_IMAGING_TECHNICIAN";

    private void signInAs(UUID sessionUserId, String... authorities) {
        com.example.hms.model.User sessionUser = new com.example.hms.model.User();
        sessionUser.setId(sessionUserId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(sessionUser, null,
                java.util.Arrays.stream(authorities)
                    .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
        // Review of #1243: a role counts at the hospital it is held at. The roles named are held
        // at this test's hospital; tokenOnly() signs in with roles held elsewhere.
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(sessionUserId);
        for (String authority : authorities) {
            lenient().when(roleValidator.hasRole(sessionUserId, hospitalId, authority.substring("ROLE_".length())))
                .thenReturn(true);
        }
    }

    /** A session whose token carries these roles, none of them held at this test's hospital. */
    private void tokenOnly(UUID sessionUserId, String... authorities) {
        com.example.hms.model.User sessionUser = new com.example.hms.model.User();
        sessionUser.setId(sessionUserId);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(sessionUser, null,
                java.util.Arrays.stream(authorities)
                    .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(sessionUserId);
        // QA D2915: held nowhere this test acts, whatever the token names.
        lenient().when(roleValidator.interpretsImaging(eq(sessionUserId), any())).thenReturn(false);
        lenient().when(roleValidator.readsImagingClinically(eq(sessionUserId), any())).thenReturn(false);
        lenient().when(roleValidator.ordersImaging(eq(sessionUserId), any())).thenReturn(false);
        lenient().when(roleValidator.isSigningPhysician(eq(sessionUserId), any())).thenReturn(false);
        lenient().when(roleValidator.administersImagingReads(eq(sessionUserId), any())).thenReturn(false);
    }

    private ImagingOrder studyOfAnotherProvider(ImagingOrderStatus status) {
        ImagingOrder order = orderAt(hospital);
        order.setStatus(status);
        order.setOrderingProviderUserId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        org.mockito.Mockito.lenient().when(imagingOrderRepository.save(order)).thenReturn(order);
        return order;
    }

    private static ImagingOrderStatusUpdateRequestDTO step(ImagingOrderStatus status) {
        return ImagingOrderStatusUpdateRequestDTO.builder().status(status).build();
    }

    @Test
    void policyRule3_aTechnicianStartsAndCompletesTheExam_andIsRecordedAsWhoPerformedIt() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID technician = UUID.randomUUID();
        signer(technician, "Awa", "Kone");
        com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
        staff.setId(UUID.randomUUID());
        when(staffRepository.findByUserIdAndHospitalId(technician, hospitalId)).thenReturn(Optional.of(staff));
        signInAs(technician, TECHNICIAN);

        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS));

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
        // The never-filled "performed by": the session user and their staff row, never a request field.
        assertThat(order.getPerformedByUserId()).isEqualTo(technician);
        assertThat(order.getPerformedByStaffId()).isEqualTo(staff.getId());
        assertThat(order.getPerformedByName()).isEqualTo("Awa Kone");
        assertThat(order.getExamStartedAt()).isNotNull();

        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.COMPLETED));
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isNotNull();
        assertThat(order.getPerformedByUserId()).isEqualTo(technician);
    }

    @Test
    void policyRule3_aRequestNamingAnotherPerformerChangesNothing() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID technician = UUID.randomUUID();
        signer(technician, "Awa", "Kone");
        signInAs(technician, TECHNICIAN);

        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.IN_PROGRESS).performedByUserId(UUID.randomUUID()).performedByName("Dr Autre")
            .build());

        assertThat(order.getPerformedByUserId()).isEqualTo(technician);
        assertThat(order.getPerformedByName()).isEqualTo("Awa Kone");
    }

    @Test
    void policyRule3_aTechnicianDiscontinuesAStartedExamWithAReason_andNeverCancelsOneNotStarted() {
        UUID technician = UUID.randomUUID();
        signer(technician, "Awa", "Kone");
        signInAs(technician, TECHNICIAN);

        ImagingOrder placed = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        ImagingOrderStatusUpdateRequestDTO cancel = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.CANCELLED).cancellationReason("Patient agité").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, cancel))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(placed.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        verify(imagingOrderRepository, never()).save(any());

        ImagingOrder started = studyOfAnotherProvider(ImagingOrderStatus.IN_PROGRESS);
        // The reason is still required of them.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.CANCELLED)))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        assertThat(started.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);

        imagingOrderService.updateOrderStatus(orderId, cancel);
        assertThat(started.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        assertThat(started.getCancellationReason()).isEqualTo("Patient agité");
        assertThat(started.getCancelledByUserId()).isEqualTo(technician);
    }

    @Test
    void policyRule3_aTechnicianNeitherSchedulesNorRecordsAPreAuthorisation() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        signInAs(UUID.randomUUID(), TECHNICIAN);
        List<ImagingOrderStatusUpdateRequestDTO> notTheirs = List.of(
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.SCHEDULED)
                .scheduledDate(LocalDate.now().plusDays(1)).build(),
            step(ImagingOrderStatus.PENDING_AUTHORIZATION),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.IN_PROGRESS)
                .authorizationNumber("AUTH-9").build(),
            ImagingOrderStatusUpdateRequestDTO.builder().status(ImagingOrderStatus.ORDERED)
                .appointmentLocation("Salle 4").build());
        for (ImagingOrderStatusUpdateRequestDTO request : notTheirs) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, request))
                .as(request.toString())
                .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        }
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        assertThat(order.getAuthorizationNumber()).isNull();
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void policyRule3_aRadiologistWhoIsAlsoATechnicianKeepsTheRadiologistsReach() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID both = UUID.randomUUID();
        signer(both, "Ali", "Sana");
        signInAs(both, TECHNICIAN, "ROLE_RADIOLOGIST");

        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.SCHEDULED).scheduledDate(LocalDate.now().plusDays(1)).build());

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.SCHEDULED);
    }

    @Test
    void policyRule3_thePatientIsCheckedInOnceFromTheSession() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.SCHEDULED);
        UUID technician = UUID.randomUUID();
        signer(technician, "Awa", "Kone");
        signInAs(technician, TECHNICIAN);

        imagingOrderService.checkInPatient(orderId);

        assertThat(order.getCheckedInAt()).isNotNull();
        assertThat(order.getCheckedInByUserId()).isEqualTo(technician);
        assertThat(order.getCheckedInByName()).isEqualTo("Awa Kone");
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.SCHEDULED);

        // A second check-in, by somebody else, changes nothing.
        LocalDateTime first = order.getCheckedInAt();
        signInAs(UUID.randomUUID(), TECHNICIAN);
        imagingOrderService.checkInPatient(orderId);
        assertThat(order.getCheckedInAt()).isEqualTo(first);
        assertThat(order.getCheckedInByUserId()).isEqualTo(technician);
        verify(imagingOrderRepository, times(1)).save(order);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ImagingOrderStatus.class, names = {"DRAFT", "COMPLETED", "RESULTS_AVAILABLE", "CANCELLED"})
    void policyRule3_noCheckInOnAnExamThatIsNotPlacedOrAlreadyDone(ImagingOrderStatus status) {
        ImagingOrder order = studyOfAnotherProvider(status);
        signInAs(UUID.randomUUID(), TECHNICIAN);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.checkInPatient(orderId))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        assertThat(order.getCheckedInAt()).isNull();
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void policyRule3_theExamRecordIsWrittenFromTheSessionAndReplacedAsSent() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.IN_PROGRESS);
        UUID technician = UUID.randomUUID();
        signInAs(technician, TECHNICIAN);

        imagingOrderService.recordExam(orderId, com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
            .technicalNote("  Thorax face, 110 kV  ").contrastAdministered(true).contrastDetails("Iode 80 mL IV")
            .radiationDoseMgy(new java.math.BigDecimal("0.150")).build());

        assertThat(order.getExamTechnicalNote()).isEqualTo("Thorax face, 110 kV");
        assertThat(order.getExamContrastAdministered()).isTrue();
        assertThat(order.getExamContrastDetails()).isEqualTo("Iode 80 mL IV");
        assertThat(order.getExamRadiationDoseMgy()).isEqualByComparingTo("0.150");
        assertThat(order.getExamRecordedByUserId()).isEqualTo(technician);
        assertThat(order.getExamRecordedAt()).isNotNull();
        // Recording the exam is not a status change.
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);

        imagingOrderService.recordExam(orderId, com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
            .technicalNote(" ").contrastAdministered(false).build());
        assertThat(order.getExamTechnicalNote()).isNull();
        assertThat(order.getExamContrastAdministered()).isFalse();
        assertThat(order.getExamContrastDetails()).isNull();
        assertThat(order.getExamRadiationDoseMgy()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ImagingOrderStatus.class, names = {"DRAFT", "ORDERED", "SCHEDULED", "RESULTS_AVAILABLE",
        "CANCELLED"})
    void policyRule3_theExamRecordIsClosedBeforeTheExamStartsAndOnceItIsRead(ImagingOrderStatus status) {
        ImagingOrder order = studyOfAnotherProvider(status);
        signInAs(UUID.randomUUID(), TECHNICIAN);

        com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO anExamRecord =
            com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder().technicalNote("x").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.recordExam(orderId, anExamRecord))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        assertThat(order.getExamTechnicalNote()).isNull();
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void policyRule3_theExamRecordAndCheckInOfAnotherHospitalsOrderAnswerAsMissing() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        ImagingOrder foreign = orderAt(other);
        foreign.setStatus(ImagingOrderStatus.IN_PROGRESS);
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(foreign));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        signInAs(UUID.randomUUID(), TECHNICIAN);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.checkInPatient(orderId))
            .isInstanceOf(ResourceNotFoundException.class);
        com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO anExamRecord =
            com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder().technicalNote("x").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.recordExam(orderId, anExamRecord))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void policyRule3_aTechnicianNeverReadsAnotherHospitalsOrder_whereTheCareTeamFollowsThePatient() {
        UUID hospitalB = UUID.randomUUID();
        ImagingOrder orderAtA = orderAtHospital(orderId, hospital);
        orderAtA.setStatus(ImagingOrderStatus.ORDERED);
        when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(orderAtA));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        UUID technician = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(technician);
        org.mockito.Mockito.lenient().when(recordAccessPolicy.readableHospitalIds(technician, patientId, hospitalB))
            .thenReturn(java.util.Set.of(hospitalB, hospitalId));
        tokenOnly(technician, TECHNICIAN);
        // A technician of hospital B, where they act.
        lenient().when(roleValidator.hasRole(technician, hospitalB, "IMAGING_TECHNICIAN")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.getOrder(orderId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(reachRecorder, never()).recordReach(any(), any(), any(), any(), any(), any());
    }

    // ═══════════════ Review of #1243: the role counts at the order's hospital ═══════════════

    @Test
    void review1243_aTechnicianOfAnotherHospitalIsRefusedEveryExamWriteAndReadHere() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        // Their token carries the technician role (held at another hospital) and nothing the imaging endpoints take.
        tokenOnly(UUID.randomUUID(), TECHNICIAN, "ROLE_RECEPTIONIST");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        lenient().when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        ImagingOrderStatusUpdateRequestDTO start = step(ImagingOrderStatus.IN_PROGRESS);
        com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO anExamRecord =
            com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder().technicalNote("x").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, start))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.checkInPatient(orderId))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        order.setStatus(ImagingOrderStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.recordExam(orderId, anExamRecord))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.getOrder(orderId))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.getOrdersByHospital(hospitalId, null))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);

        assertThat(order.getCheckedInAt()).isNull();
        assertThat(order.getExamTechnicalNote()).isNull();
        verify(imagingOrderRepository, never()).save(any());
        verify(imagingOrderRepository, never()).findByHospital_IdOrderByOrderedAtDesc(any());
    }

    @Test
    void review1243_aTechnicianHereWhoIsARadiologistElsewhereIsStillNarrowedHere() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID caller = UUID.randomUUID();
        signer(caller, "Ali", "Sana");
        // The token carries both roles; only the technician's is held at this hospital.
        tokenOnly(caller, TECHNICIAN, "ROLE_RADIOLOGIST");
        lenient().when(roleValidator.hasRole(caller, hospitalId, "IMAGING_TECHNICIAN")).thenReturn(true);
        ImagingOrderStatusUpdateRequestDTO schedule = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.SCHEDULED).scheduledDate(LocalDate.now().plusDays(1)).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, schedule))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);

        // The technician's own step still goes through.
        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS));
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
        assertThat(order.getPerformedByUserId()).isEqualTo(caller);
    }

    // ═══════════════ QA D2915 (r66): the radiologist's role too counts where it is held ═══════════════

    private static final Class<com.example.hms.exception.ClientSafeAccessDeniedException> REFUSED =
        com.example.hms.exception.ClientSafeAccessDeniedException.class;

    /** The D2915 caller: a technician at this test's hospital, a radiologist at another, the RADIOLOGIST role picked at login. */
    private UUID technicianHereRadiologistElsewhereUnderTheRadiologistRole() {
        UUID caller = UUID.randomUUID();
        tokenOnly(caller, "ROLE_RADIOLOGIST");
        lenient().when(roleValidator.hasRole(caller, hospitalId, "IMAGING_TECHNICIAN")).thenReturn(true);
        lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        return caller;
    }

    @Test
    void d2915_aTechnicianAtAWhoIsARadiologistAtB_actingAtAUnderTheRadiologistRole_schedulesAndAuthorisesNothing() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        order.setRequiresAuthorization(true);
        technicianHereRadiologistElsewhereUnderTheRadiologistRole();
        ImagingOrderStatusUpdateRequestDTO schedule = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.SCHEDULED).scheduledDate(LocalDate.now().plusDays(1)).build();
        com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO number =
            com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO.builder()
                .authorizationNumber("PA-1").build();
        ImagingOrderStatusUpdateRequestDTO cancel = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.CANCELLED).cancellationReason("x").build();

        // PUT /imaging/orders/{id}/status SCHEDULED answered 200; PUT /orders/{id}/authorization too.
        assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, schedule)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.recordAuthorization(orderId, number)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, cancel)).isInstanceOf(REFUSED);

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        assertThat(order.getScheduledDate()).isNull();
        assertThat(order.getAuthorizationNumber()).isNull();
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void d2915_theSameCallerAtA_ordersSignsAndReadsNoChartThere_andKeepsTheTechniciansWork() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID caller = technicianHereRadiologistElsewhereUnderTheRadiologistRole();
        signer(caller, "Ali", "Sana");
        lenient().when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        ImagingOrderRequestDTO request = requestWithVisit(null);
        ImagingOrderSignatureRequestDTO signature =
            ImagingOrderSignatureRequestDTO.builder().attestationConfirmed(Boolean.TRUE).build();

        assertThatThrownBy(() -> imagingOrderService.createOrder(request, caller)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.updateOrder(orderId, request, caller)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.previewDuplicates(patientId, hospitalId, ImagingModality.XRAY,
            null, 30)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.captureProviderSignature(orderId, signature, caller))
            .isInstanceOf(REFUSED);
        // A technician has no chart list, whatever role the token names.
        assertThatThrownBy(() -> imagingOrderService.getOrdersByPatient(patientId, null)).isInstanceOf(REFUSED);
        verify(imagingOrderRepository, never()).save(any());
        verify(recordAccessPolicy, never()).readableHospitalIds(any(), any(), any());

        // What a technician of A does still goes through: the worklist, the order, the exam.
        imagingOrderService.getOrder(orderId);
        imagingOrderService.getOrdersByHospital(hospitalId, null);
        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS));
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
        assertThat(order.getPerformedByUserId()).isEqualTo(caller);
    }

    @Test
    void d2915_theSameCallerActingAtB_whereTheyAreTheRadiologist_schedulesAndAuthorises() {
        // This test's hospital is now B: the radiologist's role is held here, the technician's elsewhere.
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        order.setRequiresAuthorization(true);
        UUID caller = UUID.randomUUID();
        signer(caller, "Ali", "Sana");
        // Whichever role was picked at login: what they are HERE decides.
        for (String selected : new String[] {"ROLE_RADIOLOGIST", TECHNICIAN}) {
            tokenOnly(caller, selected);
            when(roleValidator.interpretsImaging(caller, hospitalId)).thenReturn(true);
            order.setStatus(ImagingOrderStatus.ORDERED);
            order.setAuthorizationNumber(null);

            imagingOrderService.recordAuthorization(orderId,
                com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO.builder()
                    .authorizationNumber("PA-1").build());
            imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
                .status(ImagingOrderStatus.SCHEDULED).scheduledDate(LocalDate.now().plusDays(1)).build());

            assertThat(order.getAuthorizationNumber()).as(selected).isEqualTo("PA-1");
            assertThat(order.getStatus()).as(selected).isEqualTo(ImagingOrderStatus.SCHEDULED);
        }
    }

    @Test
    void d2915_aDoctorOfAnotherHospitalWhoIsAReceptionistHereDoesNothingOnImagingHere() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID caller = UUID.randomUUID();
        tokenOnly(caller, "ROLE_DOCTOR");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        lenient().when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        ImagingOrderRequestDTO request = requestWithVisit(null);

        assertThatThrownBy(() -> imagingOrderService.createOrder(request, caller)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS)))
            .isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.checkInPatient(orderId)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.getOrder(orderId)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.getOrdersByHospital(hospitalId, null)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.getOrdersByPatient(patientId, null)).isInstanceOf(REFUSED);
        verify(imagingOrderRepository, never()).save(any());
    }

    // ═══════════════ Review of #1251: the administrator's role counts where it is held ═══════════════

    @Test
    void review1251_aHospitalAdminOfAnotherHospitalReadsNoWorklistAndNoOrderHere_andTheAdminOfThisOneDoes() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        UUID caller = UUID.randomUUID();
        // A hospital admin at B, a receptionist here; the admin role was picked at login.
        tokenOnly(caller, "ROLE_HOSPITAL_ADMIN");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        lenient().when(imagingOrderRepository.findByIdUnscoped(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> imagingOrderService.getOrder(orderId)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.getOrdersByHospital(hospitalId, null)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.getAllOrders(null)).isInstanceOf(REFUSED);
        assertThatThrownBy(() -> imagingOrderService.getOrdersByPatient(patientId, null)).isInstanceOf(REFUSED);
        verify(imagingOrderRepository, never()).findByHospital_IdOrderByOrderedAtDesc(any());

        // The hospital admin OF this hospital reads its orders, as before.
        when(roleValidator.administersImagingReads(caller, hospitalId)).thenReturn(true);
        imagingOrderService.getOrder(orderId);
        imagingOrderService.getOrdersByHospital(hospitalId, null);
        verify(imagingOrderRepository).findByHospital_IdOrderByOrderedAtDesc(hospitalId);
    }

    // ═══════════════ Owner decision 2026-11-20: who performs an ultrasound ═══════════════

    @Test
    void ultrasound_aTechnicianIsRefusedTheExamAndItsRecordWhileTheHospitalKeepsItWithPhysicians() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        order.setModality(ImagingModality.ULTRASOUND);
        UUID technician = UUID.randomUUID();
        signer(technician, "Awa", "Kone");
        signInAs(technician, TECHNICIAN);
        com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO anExamRecord =
            com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder().technicalNote("x").build();

        // The default: physicians only.
        assertThat(hospital.isUltrasoundByNonPhysician()).isFalse();
        ImagingOrderStatusUpdateRequestDTO start = step(ImagingOrderStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, start))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        order.setStatus(ImagingOrderStatus.IN_PROGRESS);
        ImagingOrderStatusUpdateRequestDTO complete = step(ImagingOrderStatus.COMPLETED);
        ImagingOrderStatusUpdateRequestDTO discontinue = ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.CANCELLED).cancellationReason("Patient agité").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, complete))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.updateOrderStatus(orderId, discontinue))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imagingOrderService.recordExam(orderId, anExamRecord))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
        assertThat(order.getPerformedByUserId()).isNull();
        verify(imagingOrderRepository, never()).save(any());

        // The arrival is not the exam: it is still recorded.
        imagingOrderService.checkInPatient(orderId);
        assertThat(order.getCheckedInByUserId()).isEqualTo(technician);
    }

    @Test
    void ultrasound_aTechnicianPerformsItOnceTheHospitalAllowsIt_andAnotherModalityAlways() {
        UUID technician = UUID.randomUUID();
        signer(technician, "Awa", "Kone");
        signInAs(technician, TECHNICIAN);

        ImagingOrder ultrasound = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        ultrasound.setModality(ImagingModality.ULTRASOUND);
        hospital.setUltrasoundByNonPhysician(true);
        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS));
        assertThat(ultrasound.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
        assertThat(ultrasound.getPerformedByUserId()).isEqualTo(technician);

        hospital.setUltrasoundByNonPhysician(false);
        ImagingOrder xray = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS));
        assertThat(xray.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
    }

    @Test
    void ultrasound_aRadiologistIsNeverHeldToTheSetting() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.ORDERED);
        order.setModality(ImagingModality.ULTRASOUND);
        UUID radiologist = UUID.randomUUID();
        signer(radiologist, "Ali", "Sana");
        signInAs(radiologist, "ROLE_RADIOLOGIST");

        imagingOrderService.updateOrderStatus(orderId, step(ImagingOrderStatus.IN_PROGRESS));

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.IN_PROGRESS);
    }

    // ═══════════════ Review of #1243: the performer, and the exam record's audit ═══════════════

    @Test
    void review1243_aSameStatusWriteOnAStudyInProgressNeverReplacesWhoPerformedIt() {
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.IN_PROGRESS);
        UUID performer = UUID.randomUUID();
        LocalDateTime started = LocalDateTime.now().minusMinutes(20);
        order.setPerformedByUserId(performer);
        order.setPerformedByName("Awa Kone");
        order.setExamStartedAt(started);
        UUID radiologist = UUID.randomUUID();
        signer(radiologist, "Ali", "Sana");
        signInAs(radiologist, "ROLE_RADIOLOGIST");

        // A radiologist records where the study in progress continues: a same-status write.
        imagingOrderService.updateOrderStatus(orderId, ImagingOrderStatusUpdateRequestDTO.builder()
            .status(ImagingOrderStatus.IN_PROGRESS).appointmentLocation("Salle 2").build());

        assertThat(order.getAppointmentLocation()).isEqualTo("Salle 2");
        assertThat(order.getPerformedByUserId()).isEqualTo(performer);
        assertThat(order.getPerformedByName()).isEqualTo("Awa Kone");
        assertThat(order.getExamStartedAt()).isEqualTo(started);
    }

    @Test
    void review1243_anExamRecordWriteIsAuditedWithWhatChanged_neverTheFreeText() {
        com.example.hms.service.AuditEventLogService audit = mock(com.example.hms.service.AuditEventLogService.class);
        imagingOrderService.setAuditEventLogService(audit);
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.IN_PROGRESS);
        order.setExamContrastAdministered(false);
        order.setExamRadiationDoseMgy(new java.math.BigDecimal("0.100"));
        UUID technician = UUID.randomUUID();
        signInAs(technician, TECHNICIAN);

        imagingOrderService.recordExam(orderId, com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
            .technicalNote("Patient Awa Kone agitée, thorax face").contrastAdministered(true)
            .contrastDetails("Iode 80 mL IV").radiationDoseMgy(new java.math.BigDecimal("0.150")).build());

        org.mockito.ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> event =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.DATA_UPDATE);
        assertThat(event.getValue().getEntityType()).isEqualTo("IMAGING_ORDER");
        assertThat(event.getValue().getResourceId()).isEqualTo(orderId.toString());
        assertThat(event.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(event.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(event.getValue().getUserId()).isEqualTo(technician);
        assertThat(event.getValue().getEventDescription()).isEqualTo("Imaging exam record updated");
        assertThat(event.getValue().getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("contrastAdministered", "false -> true")
            .containsEntry("radiationDoseMgy", "0.1 -> 0.15")
            .containsEntry("technicalNote", "set")
            .containsEntry("contrastDetails", "set");
        assertThat(event.getValue().getDetails().toString()).doesNotContain("Awa").doesNotContain("Iode");

        // The same record again changes nothing: no second row.
        imagingOrderService.recordExam(orderId, com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
            .technicalNote("Patient Awa Kone agitée, thorax face").contrastAdministered(true)
            .contrastDetails("Iode 80 mL IV").radiationDoseMgy(new java.math.BigDecimal("0.15")).build());
        verify(audit, times(1)).logEvent(any());
    }

    @Test
    void review1243_aFailedAuditNeverFailsTheExamRecord() {
        com.example.hms.service.AuditEventLogService audit = mock(com.example.hms.service.AuditEventLogService.class);
        doThrow(new IllegalStateException("audit down")).when(audit).logEvent(any());
        imagingOrderService.setAuditEventLogService(audit);
        ImagingOrder order = studyOfAnotherProvider(ImagingOrderStatus.IN_PROGRESS);
        signInAs(UUID.randomUUID(), TECHNICIAN);

        imagingOrderService.recordExam(orderId, com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO.builder()
            .technicalNote("RAS").build());

        assertThat(order.getExamTechnicalNote()).isEqualTo("RAS");
    }
}
