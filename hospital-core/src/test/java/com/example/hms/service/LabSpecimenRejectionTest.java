package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabSpecimenMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.lab.LabSpecimenRejectionNotifier;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

/** QA D962: a specimen can be rejected with a reason, and the order goes back to collection. */
@ExtendWith(MockitoExtension.class)
class LabSpecimenRejectionTest {

    @Mock private LabSpecimenRepository labSpecimenRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabSpecimenMapper labSpecimenMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private InstrumentOutboxService instrumentOutboxService;
    @Mock private LabSpecimenRejectionNotifier rejectionNotifier;
    @Mock private com.example.hms.repository.LabResultRepository labResultRepository;

    @InjectMocks
    private LabSpecimenServiceImpl service;

    private UUID hospitalId;
    private UUID actorId;
    private LabOrder labOrder;
    private LabSpecimen specimen;

    @BeforeEach
    void setUp() {
        // Review of #975: the committed status read under the order lock is
        // the entity's own unless a test says otherwise (no concurrent write).
        org.mockito.Mockito.lenient().when(labSpecimenRepository.findStatusById(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> labSpecimenRepository.findById(inv.getArgument(0))
                .map(com.example.hms.model.LabSpecimen::getStatus).orElse(null));
        hospitalId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospitalId);
        labOrder = LabOrder.builder().hospital(hospital).build();
        labOrder.setId(UUID.randomUUID());
        labOrder.setStatus(LabOrderStatus.RECEIVED);
        specimen = LabSpecimen.builder().labOrder(labOrder).status(LabSpecimenStatus.RECEIVED).build();
        specimen.setId(UUID.randomUUID());
        when(labSpecimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private void stubTheWrite(LabOrderStatus committed) {
        when(labOrderRepository.findStatusById(labOrder.getId())).thenReturn(committed);
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(actorId);
        lenient().when(labSpecimenRepository.save(specimen)).thenReturn(specimen);
        lenient().when(labSpecimenRepository.findByLabOrder_Id(labOrder.getId())).thenReturn(List.of(specimen));
        lenient().when(labOrderRepository.updateStatusFrom(eq(labOrder.getId()), any(), any())).thenReturn(1);
        lenient().when(labSpecimenMapper.toResponseDTO(specimen))
            .thenReturn(LabSpecimenResponseDTO.builder().id(specimen.getId()).build());
    }

    @Test
    @DisplayName("QA D962 — a rejection records why, who and when, sends the order back to ORDERED and tells the doctor")
    void aRejectionIsRecordedAndTheOrderGoesBackToCollection() {
        stubTheWrite(LabOrderStatus.RECEIVED);

        service.rejectSpecimen(specimen.getId(), "  Hémolysé  ", Locale.FRENCH);

        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.REJECTED);
        assertThat(specimen.getRejectionReason()).isEqualTo("Hémolysé");
        assertThat(specimen.getRejectedById()).isEqualTo(actorId);
        assertThat(specimen.getRejectedAt()).isNotNull();
        verify(labOrderRepository).findWithLockById(labOrder.getId());
        verify(labOrderRepository).updateStatusFrom(labOrder.getId(), LabOrderStatus.RECEIVED, LabOrderStatus.ORDERED);
        verify(rejectionNotifier).specimenRejected(specimen);
    }

    @Test
    @DisplayName("QA D962 — a second specimen still standing keeps the order where it is")
    void anotherLiveSpecimenKeepsTheOrder() {
        stubTheWrite(LabOrderStatus.RECEIVED);
        LabSpecimen second = LabSpecimen.builder().labOrder(labOrder).status(LabSpecimenStatus.COLLECTED).build();
        second.setId(UUID.randomUUID());
        when(labSpecimenRepository.findByLabOrder_Id(labOrder.getId())).thenReturn(List.of(specimen, second));

        service.rejectSpecimen(specimen.getId(), "Tube inadapté", Locale.FRENCH);

        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.REJECTED);
        verify(labOrderRepository, never()).updateStatusFrom(any(), any(), any());
    }

    @Test
    @DisplayName("QA D962 — the reason is mandatory, nothing is written without it")
    void aReasonIsRequired() {
        UUID id = specimen.getId();
        assertThatThrownBy(() -> service.rejectSpecimen(id, "   ", Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.rejectReasonRequired");
        String overlong = "x".repeat(501);
        assertThatThrownBy(() -> service.rejectSpecimen(id, overlong, Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.rejectReasonTooLong");
        verify(labSpecimenRepository, never()).save(any());
        assertThat(specimen.getStatus()).isEqualTo(LabSpecimenStatus.RECEIVED);
    }

    @ParameterizedTest
    @EnumSource(value = LabSpecimenStatus.class, names = {"PENDING", "PROCESSING", "COMPLETED", "REJECTED"})
    @DisplayName("QA D962 — only a collected, in-transit or received specimen can be rejected")
    void anAnalysedOrAlreadyRejectedSpecimenIsRefused(LabSpecimenStatus status) {
        specimen.setStatus(status);
        UUID id = specimen.getId();

        assertThatThrownBy(() -> service.rejectSpecimen(id, "Hémolysé", Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.notRejectable");
        verify(labSpecimenRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = LabOrderStatus.class, names = {"RESULTED", "VERIFIED", "COMPLETED"})
    @DisplayName("QA D962 — once a result exists the result is corrected, the specimen is not rejected")
    void aResultedOrderRefusesARejection(LabOrderStatus committed) {
        when(labOrderRepository.findStatusById(labOrder.getId())).thenReturn(committed);
        UUID id = specimen.getId();

        assertThatThrownBy(() -> service.rejectSpecimen(id, "Hémolysé", Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.rejectAfterResult");
        verify(labSpecimenRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D962 — another hospital's specimen reads as not found")
    void anotherHospitalsSpecimenIsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        UUID id = specimen.getId();

        assertThatThrownBy(() -> service.rejectSpecimen(id, "Hémolysé", Locale.FRENCH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labSpecimenRepository, never()).save(any());
    }

    @Test
    @DisplayName("Review of #975 — a receipt or rejection that committed after the specimen was read is not written over")
    void aSpecimenChangedSinceItWasReadIsNotWrittenOver() {
        specimen.setStatus(LabSpecimenStatus.COLLECTED);
        // Received by a colleague between this request's read and its lock.
        org.mockito.Mockito.doReturn(LabSpecimenStatus.RECEIVED).when(labSpecimenRepository).findStatusById(specimen.getId());
        UUID id = specimen.getId();

        assertThatThrownBy(() -> service.rejectSpecimen(id, "Hémolysé", Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.changedConcurrently");
        verify(labOrderRepository).findWithLockById(labOrder.getId());
        verify(labSpecimenRepository, never()).save(any());
        verify(labOrderRepository, never()).updateStatusFrom(any(), any(), any());
        verify(rejectionNotifier, never()).specimenRejected(any());
    }

    @Test
    @DisplayName("Review of #975 — a specimen rejected since it was read is not received, and no work order is queued")
    void aSpecimenRejectedSinceItWasReadIsNotReceived() {
        specimen.setStatus(LabSpecimenStatus.COLLECTED);
        org.mockito.Mockito.doReturn(LabSpecimenStatus.REJECTED).when(labSpecimenRepository).findStatusById(specimen.getId());
        UUID id = specimen.getId();

        assertThatThrownBy(() -> service.receiveSpecimen(id, Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.changedConcurrently");
        verify(labSpecimenRepository, never()).save(any());
        verify(instrumentOutboxService, never()).enqueueSpecimenReceived(any());
    }

    @Test
    @DisplayName("Review of #975 — a tube whose analyser already sent a (preliminary) result is corrected, not rejected")
    void aRecordedResultBarsTheRejection() {
        when(labOrderRepository.findStatusById(labOrder.getId())).thenReturn(LabOrderStatus.IN_PROGRESS);
        when(labResultRepository.existsByLabOrder_Id(labOrder.getId())).thenReturn(true);
        UUID id = specimen.getId();

        assertThatThrownBy(() -> service.rejectSpecimen(id, "Hémolysé", Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "lab.specimen.rejectAfterResult");
        verify(labSpecimenRepository, never()).save(any());
        verify(labOrderRepository, never()).updateStatusFrom(any(), any(), any());
    }
}
