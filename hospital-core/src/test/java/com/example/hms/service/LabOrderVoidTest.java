package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D820: LAB-000035 was COMPLETED and could be neither cancelled nor voided;
 * only an admin DELETE removed it. {@code voidLabOrder} marks it entered in
 * error with a reason, from any state, and a delete never removes a completed
 * or voided order.
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class LabOrderVoidTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabOrderMapper labOrderMapper;
    @Mock private RoleValidator roleValidator;

    @InjectMocks
    private LabOrderServiceImpl service;

    private Hospital ordering;
    private Hospital other;
    private LabOrder order;
    private final UUID managerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ordering = Hospital.builder().name("CMA Pissy").code("ORD").build();
        ordering.setId(UUID.randomUUID());
        other = Hospital.builder().name("CHU Yalgado").code("OTH").build();
        other.setId(UUID.randomUUID());
        order = LabOrder.builder().hospital(ordering).status(LabOrderStatus.COMPLETED).build();
        order.setId(UUID.randomUUID());
        lenient().when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(labOrderRepository.findStatusById(order.getId())).thenAnswer(inv -> order.getStatus());
        lenient().when(labOrderRepository.save(order)).thenReturn(order);
        lenient().when(labOrderMapper.toLabOrderResponseDTO(order))
            .thenAnswer(inv -> LabOrderResponseDTO.builder().id(order.getId().toString())
                .status(order.getStatus().name()).cancellationReason(order.getCancellationReason()).build());
        lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(managerId);
    }

    @Test
    void theLabManagerVoidsTheCompletedOrderWithAReason() {
        when(roleValidator.isLabManager(managerId, ordering.getId())).thenReturn(true);

        LabOrderResponseDTO dto = service.voidLabOrder(order.getId(), "  Wrong patient (duplicate record)  ",
            Locale.FRENCH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ENTERED_IN_ERROR);
        assertThat(order.getCancellationReason()).isEqualTo("Wrong patient (duplicate record)");
        assertThat(dto.getStatus()).isEqualTo("ENTERED_IN_ERROR");
        verify(labOrderRepository).save(order);
        verify(labOrderRepository, never()).deleteById(any());
    }

    @ParameterizedTest
    @EnumSource(value = LabOrderStatus.class, names = {"ENTERED_IN_ERROR", "CANCELLED"},
        mode = EnumSource.Mode.EXCLUDE)
    void anyStateBeforeCanBeVoided(LabOrderStatus from) {
        order.setStatus(from);
        when(roleValidator.isLabDirector(managerId, ordering.getId())).thenReturn(true);

        service.voidLabOrder(order.getId(), "Wrong test", Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ENTERED_IN_ERROR);
    }

    @Test
    void aSuperAdminMayVoid() {
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);

        service.voidLabOrder(order.getId(), "Wrong patient", Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ENTERED_IN_ERROR);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void aVoidStatesWhy(String reason) {
        when(roleValidator.isLabManager(managerId, ordering.getId())).thenReturn(true);
        UUID id = order.getId();

        assertThatThrownBy(() -> service.voidLabOrder(id, reason, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.voidReasonRequired"));
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void anOverlongReasonIsRefused() {
        when(roleValidator.isLabManager(managerId, ordering.getId())).thenReturn(true);
        UUID id = order.getId();
        String reason = "x".repeat(1001);

        assertThatThrownBy(() -> service.voidLabOrder(id, reason, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.voidReasonTooLong"));
    }

    @Test
    void aNurseOrAHospitalAdminDoesNotVoid() {
        UUID id = order.getId();

        assertThatThrownBy(() -> service.voidLabOrder(id, "Wrong patient", Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.voidForbidden"));
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
    }

    @Test
    void theAuthorityIsAskedAtTheOrderingHospital() {
        // Acting at another hospital (a performing laboratory) the order is not
        // in scope at all: 404, never a void.
        when(roleValidator.requireActiveHospitalId()).thenReturn(other.getId());
        UUID id = order.getId();

        assertThatThrownBy(() -> service.voidLabOrder(id, "Wrong patient", Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
    }

    @Test
    void aVoidedOrderIsNotVoidedTwice() {
        order.setStatus(LabOrderStatus.ENTERED_IN_ERROR);
        order.setCancellationReason("first");
        UUID id = order.getId();

        assertThatThrownBy(() -> service.voidLabOrder(id, "second", Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.alreadyVoided"));
        assertThat(order.getCancellationReason()).isEqualTo("first");
    }

    @Test
    void aCancelledOrderKeepsItsCancelReason() {
        order.setStatus(LabOrderStatus.CANCELLED);
        order.setCancellationReason("Test withdrawn");
        UUID id = order.getId();

        assertThatThrownBy(() -> service.voidLabOrder(id, "Wrong patient", Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.voidCancelled"));
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Test withdrawn");
    }

    @Test
    void anUnknownOrderIs404() {
        UUID unknown = UUID.randomUUID();
        when(labOrderRepository.findWithLockById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.voidLabOrder(unknown, "x", Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theVoidedStateIsNotReachableThroughTheTransitionEndpoint() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID id = order.getId();

        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.ENTERED_IN_ERROR,
                "x", Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
    }

    @Test
    void aVoidedOrderIsNotEdited() {
        order.setStatus(LabOrderStatus.ENTERED_IN_ERROR);
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        UUID id = order.getId();
        com.example.hms.payload.dto.LabOrderRequestDTO edit = new com.example.hms.payload.dto.LabOrderRequestDTO();

        assertThatThrownBy(() -> service.updateLabOrder(id, edit, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getMessageKey())
                .isEqualTo("lab.order.enteredInError"));
        verify(labOrderRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = LabOrderStatus.class, names = {"COMPLETED", "ENTERED_IN_ERROR"})
    void aCompletedOrVoidedOrderIsNeverDeleted(LabOrderStatus status) {
        // A voided order's results no longer load, so the results check alone
        // would have let its delete through.
        order.setStatus(status);
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(labResultRepository.existsByLabOrder_Id(order.getId())).thenReturn(false);
        UUID id = order.getId();

        assertThatThrownBy(() -> service.deleteLabOrder(id, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);
        verify(labOrderRepository, never()).deleteById(any());
    }

    @Test
    void anOrderWithNothingOnItIsStillDeletable() {
        order.setStatus(LabOrderStatus.ORDERED);
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(labResultRepository.existsByLabOrder_Id(order.getId())).thenReturn(false);

        service.deleteLabOrder(order.getId(), Locale.ENGLISH);

        verify(labOrderRepository).deleteById(order.getId());
    }
}
