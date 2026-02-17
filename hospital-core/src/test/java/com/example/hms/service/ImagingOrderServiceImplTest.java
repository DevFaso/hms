package com.example.hms.service;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @InjectMocks
    private ImagingOrderServiceImpl imagingOrderService;

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
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(imagingOrderMapper.toEntity(request, patient, hospital)).thenReturn(order);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), eq(BODY_REGION_CHEST), any(LocalDateTime.class)))
            .thenReturn(duplicates);
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order, duplicates)).thenReturn(responseDTO);

        ImagingOrderResponseDTO result = imagingOrderService.createOrder(request, orderingUserId);

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

        ImagingOrderResponseDTO result = imagingOrderService.updateOrderStatus(orderId, request);

        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Duplicate order");
        assertThat(order.getCancelledByUserId()).isEqualTo(performerId);
        assertThat(order.getCancelledByName()).isEqualTo("Dr. Watson");
        assertThat(order.getStatusUpdatedAt()).isNotNull();
        assertThat(order.getStatusUpdatedBy()).isEqualTo(performerId);
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

        ImagingOrderResponseDTO result = imagingOrderService.updateOrder(orderId, request);

        assertThat(order.getDuplicateOfRecentOrder()).isFalse();
        assertThat(order.getDuplicateReferenceOrderId()).isNull();
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void captureProviderSignaturePromotesDraftOrder() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        order.setStatus(ImagingOrderStatus.DRAFT);

        LocalDateTime signedAt = LocalDateTime.now().minusMinutes(5);
        ImagingOrderSignatureRequestDTO request = ImagingOrderSignatureRequestDTO.builder()
            .providerName("Dr. Strange")
            .providerNpi("5555")
            .providerUserId(UUID.randomUUID())
            .signatureStatement("Electronically signed")
            .signedAt(signedAt)
            .attestationConfirmed(Boolean.TRUE)
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        ImagingOrderResponseDTO result = imagingOrderService.captureProviderSignature(orderId, request);

        assertThat(order.getOrderingProviderName()).isEqualTo("Dr. Strange");
        assertThat(order.getOrderingProviderNpi()).isEqualTo("5555");
        assertThat(order.getOrderingProviderUserId()).isEqualTo(request.getProviderUserId());
        assertThat(order.getProviderSignatureStatement()).isEqualTo("Electronically signed");
        assertThat(order.getProviderSignedAt()).isEqualTo(signedAt);
        assertThat(order.getAttestationConfirmed()).isTrue();
        assertThat(order.getStatus()).isEqualTo(ImagingOrderStatus.ORDERED);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void getOrderReturnsResponseDTO() {
        ImagingOrder order = new ImagingOrder();
        order.setId(orderId);
        ImagingOrderResponseDTO dto = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(dto);

        ImagingOrderResponseDTO result = imagingOrderService.getOrder(orderId);

        assertThat(result.getId()).isEqualTo(orderId);
    }

    @Test
    void getOrderThrowsWhenNotFound() {
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.empty());

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
            .providerUserId(UUID.randomUUID())
            .signatureStatement("Signed")
            .signedAt(null)
            .build();

        ImagingOrderResponseDTO responseDTO = ImagingOrderResponseDTO.builder().id(orderId).build();

        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order)).thenReturn(responseDTO);

        imagingOrderService.captureProviderSignature(orderId, request);

        assertThat(order.getProviderSignedAt()).isNotNull();
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
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(imagingOrderMapper.toEntity(request, patient, hospital)).thenReturn(order);
        when(imagingOrderRepository.findPotentialDuplicates(eq(patientId), eq(ImagingModality.CT), isNull(), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());
        when(imagingOrderRepository.save(order)).thenReturn(order);
        when(imagingOrderMapper.toResponseDTO(order, Collections.emptyList())).thenReturn(responseDTO);

        imagingOrderService.createOrder(request, null);

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
}
