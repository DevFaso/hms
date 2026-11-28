package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D4148, D4149 (r81): the prescriber calls off their own lab order while nothing was collected for it,
 * and whoever withdraws an order (a cancellation, a void) tells its prescriber.
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
@DisplayName("QA D4148/D4149: the prescriber cancels before collection, and is told when the laboratory withdraws")
class LabOrderPrescriberCancelTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabOrderMapper labOrderMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.service.lab.LabResultReleaseNotifier resultReleaseNotifier;

    @InjectMocks
    private LabOrderServiceImpl service;

    private Hospital ordering;
    private LabOrder order;
    private final UUID prescriberId = UUID.randomUUID();
    private final UUID colleagueId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ordering = Hospital.builder().name("CMA Pissy").code("ORD").build();
        ordering.setId(UUID.randomUUID());
        User prescriber = new User();
        prescriber.setId(prescriberId);
        Staff orderingStaff = new Staff();
        orderingStaff.setUser(prescriber);
        orderingStaff.setHospital(ordering);
        order = LabOrder.builder().hospital(ordering).orderingStaff(orderingStaff).status(LabOrderStatus.ORDERED).build();
        order.setId(UUID.randomUUID());
        lenient().when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(labOrderRepository.findStatusById(order.getId())).thenAnswer(inv -> order.getStatus());
        lenient().when(labOrderRepository.save(order)).thenReturn(order);
        lenient().when(labOrderMapper.toLabOrderResponseDTO(order))
            .thenAnswer(inv -> LabOrderResponseDTO.builder().id(order.getId().toString())
                .status(order.getStatus().name()).cancellationReason(order.getCancellationReason()).build());
        lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        lenient().when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
    }

    private void actingAs(UUID userId) {
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
    }

    @ParameterizedTest
    @EnumSource(value = LabOrderStatus.class, names = {"ORDERED", "PENDING"})
    void thePrescriberCancelsTheirOwnOrderBeforeCollection_withAReason(LabOrderStatus before) {
        order.setStatus(before);
        actingAs(prescriberId);

        LabOrderResponseDTO dto = service.transitionLabOrderStatus(order.getId(), LabOrderStatus.CANCELLED,
            " Bilan déjà fait hier ", Locale.FRENCH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.CANCELLED);
        assertThat(dto.getCancellationReason()).isEqualTo("Bilan déjà fait hier");
        // The notifier is handed who acted: it tells nobody of their own cancellation.
        verify(resultReleaseNotifier).prescriberOrderWithdrawn(order, false, prescriberId);
    }

    @Test
    void thePrescriberStillStatesWhy() {
        actingAs(prescriberId);
        UUID id = order.getId();

        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.CANCELLED, "  ", Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ORDERED);
    }

    @ParameterizedTest
    @EnumSource(value = LabOrderStatus.class, names = {"COLLECTED", "RECEIVED", "IN_PROGRESS"})
    void onceASpecimenExistsTheOrderIsTheLaboratorysToCancel(LabOrderStatus collected) {
        order.setStatus(collected);
        actingAs(prescriberId);
        UUID id = order.getId();

        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.CANCELLED, "Changed my mind",
                Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("before the specimen is collected")
            .hasMessageContaining("laboratory manager or director");
        assertThat(order.getStatus()).isEqualTo(collected);
        verify(resultReleaseNotifier, never()).prescriberOrderWithdrawn(any(), anyBoolean(), any());
    }

    /** Review of QA D4148: a collection committed meanwhile decides, not the order as it was first read. */
    @Test
    void aCollectionCommittedMeanwhileIsNotCancelledOver() {
        order.setStatus(LabOrderStatus.PENDING);
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.COLLECTED);
        actingAs(prescriberId);
        UUID id = order.getId();

        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.CANCELLED, "Changed my mind",
                Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("lab.order.cancelForbidden");
        verify(labOrderRepository).findWithLockById(id);
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void anotherDoctorDoesNotCancelAColleaguesOrder() {
        actingAs(colleagueId);
        UUID id = order.getId();

        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.CANCELLED, "Not needed",
                Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ORDERED);
    }

    @Test
    void theLaboratorysCancellationTellsThePrescriber() {
        order.setStatus(LabOrderStatus.RECEIVED);
        actingAs(managerId);
        when(roleValidator.isLabManager(managerId, ordering.getId())).thenReturn(true);

        service.transitionLabOrderStatus(order.getId(), LabOrderStatus.CANCELLED, "Tube haemolysed", Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.CANCELLED);
        verify(resultReleaseNotifier).prescriberOrderWithdrawn(order, false, managerId);
    }

    @Test
    void aVoidTellsThePrescriberTheResultWasWithdrawn() {
        order.setStatus(LabOrderStatus.COMPLETED);
        actingAs(managerId);
        when(roleValidator.isLabDirector(managerId, ordering.getId())).thenReturn(true);

        service.voidLabOrder(order.getId(), "Wrong patient sampled", Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ENTERED_IN_ERROR);
        verify(resultReleaseNotifier).prescriberOrderWithdrawn(eq(order), eq(true), anyBoolean(), eq(managerId));
    }
}
