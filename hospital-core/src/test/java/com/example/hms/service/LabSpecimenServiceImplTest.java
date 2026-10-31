package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabSpecimenMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class LabSpecimenServiceImplTest {

    @Mock private LabSpecimenRepository labSpecimenRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabSpecimenMapper labSpecimenMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private InstrumentOutboxService instrumentOutboxService;

    @InjectMocks
    private LabSpecimenServiceImpl service;

    private UUID hospitalId;
    private UUID labOrderId;
    private UUID specimenId;
    private Hospital hospital;
    private LabOrder labOrder;
    private LabSpecimenResponseDTO responseDTO;

    /** The committed status the statement writer compares against. */
    private void givenCommittedOrderStatus(LabOrderStatus status) {
        when(labOrderRepository.findStatusById(labOrderId)).thenReturn(status);
    }

    @BeforeEach
    void setUp() {
        // Review of #975: the committed status read under the order lock is
        // the entity's own (no concurrent write). The specimens here are built
        // without an id and found under specimenId, so that is where it is read.
        lenient().when(labSpecimenRepository.findStatusById(any()))
            .thenAnswer(inv -> labSpecimenRepository.findById(specimenId)
                .map(com.example.hms.model.LabSpecimen::getStatus).orElse(null));
        hospitalId  = UUID.randomUUID();
        labOrderId  = UUID.randomUUID();
        specimenId  = UUID.randomUUID();
        hospital    = Hospital.builder().build();
        hospital.setId(hospitalId);
        labOrder    = LabOrder.builder().hospital(hospital).build();
        labOrder.setId(labOrderId);
        responseDTO = LabSpecimenResponseDTO.builder().id(specimenId).build();
    }

    // ── createSpecimen ────────────────────────────────────────────────────────

    @Test
    void createSpecimen_success() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("BLOOD")
            .build();

        LabSpecimen saved = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        givenCommittedOrderStatus(LabOrderStatus.ORDERED);
        when(labSpecimenRepository.existsByAccessionNumber(any())).thenReturn(false);
        when(labSpecimenRepository.save(any())).thenReturn(saved);
        when(labSpecimenMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.createSpecimen(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        verify(labSpecimenRepository).save(any(LabSpecimen.class));
        // B2: a collected specimen IS the order's COLLECTED state, written
        // by the same compare-and-set statement the result path uses.
        verify(labOrderRepository).updateStatusFrom(
            labOrderId, LabOrderStatus.ORDERED, LabOrderStatus.COLLECTED);
        verify(labOrderRepository, never()).save(any(LabOrder.class));
    }

    /** QA D637: collectedAt 48 h before the order is refused and nothing is saved. */
    @Test
    void createSpecimen_collectedBeforeOrder_isRefused() {
        labOrder.setOrderDatetime(java.time.LocalDateTime.now());
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("BLOOD")
            .collectedAt(java.time.LocalDateTime.now().minusHours(48))
            .build();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ordered");
        verify(labSpecimenRepository, never()).save(any());
    }

    /** QA D820: an order voided as entered in error takes no specimen. */
    @Test
    void createSpecimen_onAVoidedOrder_isRefused() {
        labOrder.setStatus(LabOrderStatus.ENTERED_IN_ERROR);
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("BLOOD")
            .build();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.enteredInError"));
        verify(labSpecimenRepository, never()).save(any());
    }

    @Test
    void createSpecimen_noLabOrderId_throwsBusinessException() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder().build(); // labOrderId is null

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("labOrderId");
    }

    @Test
    void createSpecimen_labOrderNotFound_throwsResourceNotFoundException() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSpecimen_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId); // different hospital

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSpecimen_superAdmin_skipsHospitalScopeCheck() {
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("URINE")
            .build();

        LabSpecimen saved = LabSpecimen.builder().labOrder(labOrder).build();

        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(null); // super admin
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.existsByAccessionNumber(any())).thenReturn(false);
        when(labSpecimenRepository.save(any())).thenReturn(saved);
        when(labSpecimenMapper.toResponseDTO(saved)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.createSpecimen(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
    }

    // ── getSpecimenById ───────────────────────────────────────────────────────

    @Test
    void getSpecimenById_success() {
        LabSpecimen specimen = LabSpecimen.builder().labOrder(labOrder).build();
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        LabSpecimenResponseDTO result = service.getSpecimenById(specimenId, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void getSpecimenById_notFound_throwsResourceNotFoundException() {
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSpecimenById(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSpecimenById_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        LabSpecimen specimen = LabSpecimen.builder().labOrder(labOrder).build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> service.getSpecimenById(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getSpecimensByLabOrder ────────────────────────────────────────────────

    @Test
    void getSpecimensByLabOrder_success() {
        LabSpecimen specimen = LabSpecimen.builder().labOrder(labOrder).build();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(labSpecimenRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of(specimen));
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        List<LabSpecimenResponseDTO> result = service.getSpecimensByLabOrder(labOrderId, Locale.ENGLISH);

        assertThat(result).containsExactly(responseDTO);
    }

    @Test
    void getSpecimensByLabOrder_labOrderNotFound_throwsResourceNotFoundException() {
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSpecimensByLabOrder(labOrderId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSpecimensByLabOrder_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> service.getSpecimensByLabOrder(labOrderId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── receiveSpecimen ───────────────────────────────────────────────────────

    @Test
    void receiveSpecimen_fromCollected_success() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);
        givenCommittedOrderStatus(LabOrderStatus.COLLECTED);

        LabSpecimenResponseDTO result = service.receiveSpecimen(specimenId, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.RECEIVED);
        // B2: receipt at the lab IS the order's RECEIVED state.
        verify(labOrderRepository).updateStatusFrom(
            labOrderId, LabOrderStatus.COLLECTED, LabOrderStatus.RECEIVED);
        verify(labOrderRepository, never()).save(any(LabOrder.class));
        verify(instrumentOutboxService).enqueueSpecimenReceived(specimen);
    }

    /**
     * QA D820: a specimen collected, then its order voided as entered in error.
     * Receiving it must save nothing and queue no OML^O21 work order.
     */
    @Test
    void receiveSpecimen_onAVoidedOrder_isRefused() {
        labOrder.setStatus(LabOrderStatus.ENTERED_IN_ERROR);
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.enteredInError"));
        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.COLLECTED);
        verify(labSpecimenRepository, never()).save(any());
        verify(labOrderRepository, never()).updateStatusFrom(any(), any(), any());
        verify(instrumentOutboxService, never()).enqueueSpecimenReceived(any());
    }

    /** QA D820: a void that commits after the check is seen under the order lock. */
    @Test
    void receiveSpecimen_whenTheOrderIsVoidedConcurrently_queuesNothing() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        givenCommittedOrderStatus(LabOrderStatus.ENTERED_IN_ERROR);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.enteredInError"));
        verify(labOrderRepository, never()).updateStatusFrom(any(), any(), any());
        verify(instrumentOutboxService, never()).enqueueSpecimenReceived(any());
    }

    @Test
    void receiveSpecimen_neverMovesAResultedOrderBack() {
        labOrder.setStatus(LabOrderStatus.RESULTED);
        givenCommittedOrderStatus(LabOrderStatus.RESULTED);
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        service.receiveSpecimen(specimenId, Locale.ENGLISH);

        // the committed status is past RECEIVED, so nothing is written
        verify(labOrderRepository, never()).updateStatusFrom(any(), any(), any());
        verify(labOrderRepository, never()).save(any(LabOrder.class));
    }

    @Test
    void receiveSpecimen_fromInTransit_success() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.IN_TRANSIT)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        when(labSpecimenMapper.toResponseDTO(specimen)).thenReturn(responseDTO);

        service.receiveSpecimen(specimenId, Locale.ENGLISH);

        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.RECEIVED);
        verify(instrumentOutboxService).enqueueSpecimenReceived(specimen);
    }

    @Test
    void receiveSpecimen_invalidStatus_throwsBusinessException() {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.RECEIVED) // already received → invalid transition
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be received from status");
    }

    @Test
    void receiveSpecimen_notFound_throwsResourceNotFoundException() {
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void receiveSpecimen_hospitalMismatch_throwsResourceNotFoundException() {
        UUID otherHospitalId = UUID.randomUUID();
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();

        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * QA D1109: an order that already has a result, or is cancelled, takes no
     * new specimen (the collection on a released order left a dangling tube
     * with an accession number nobody would run).
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = LabOrderStatus.class,
        names = {"RESULTED", "VERIFIED", "COMPLETED", "CANCELLED"})
    void d1109_anAnsweredOrCancelledOrderTakesNoNewSpecimen(LabOrderStatus status) {
        labOrder.setStatus(status);
        LabSpecimenRequestDTO request = LabSpecimenRequestDTO.builder()
            .labOrderId(labOrderId)
            .specimenType("BLOOD")
            .build();
        when(labOrderRepository.findById(labOrderId)).thenReturn(Optional.of(labOrder));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSpecimen(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("new specimen");
        verify(labSpecimenRepository, never()).save(any());
    }

    /** QA D1109: a tube collected before its order was cancelled is not received. */
    @Test
    void d1109_aSpecimenOfACancelledOrderIsNotReceived() {
        labOrder.setStatus(LabOrderStatus.CANCELLED);
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .status(LabSpecimenStatus.COLLECTED)
            .build();
        when(labSpecimenRepository.findById(specimenId)).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.receiveSpecimen(specimenId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cancelled");
        verify(labSpecimenRepository, never()).save(any());
    }
}
