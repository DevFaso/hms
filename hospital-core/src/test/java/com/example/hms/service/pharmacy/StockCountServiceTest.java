package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.*;
import com.example.hms.payload.dto.pharmacy.*;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.*;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
class StockCountServiceTest {
    @Mock private StockCountRepository counts;
    @Mock private InventoryItemRepository items;
    @Mock private StockLotRepository lots;
    @Mock private PharmacyRepository pharmacies;
    @Mock private UserRepository users;
    @Mock private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignments;
    @Mock private StockTransactionService movements;
    @Mock private RoleValidator roles;
    @Mock private EntityManager entityManager;
    @Mock private JdbcTemplate jdbc;
    private StockCountService service;
    private Hospital hospital;
    private InventoryItem item;
    private User actor;
    private StockCount count;

    @BeforeEach
    void fixture() {
        service = new StockCountService(counts, items, lots, pharmacies, users, assignments, movements, roles, entityManager, jdbc,
            Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC));
        hospital = new Hospital(); hospital.setId(UUID.randomUUID());
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).active(true).build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().pharmacy(pharmacy).unit("tablet").quantityOnHand(new BigDecimal("20")).build();
        item.setId(UUID.randomUUID());
        actor = new User(); actor.setId(UUID.randomUUID()); actor.setFirstName("Synthetic"); actor.setLastName("Counter");
        count = StockCount.builder().hospital(hospital).inventoryItem(item).clientRequestId(UUID.randomUUID())
            .itemRevision(0L).bookQuantity(new BigDecimal("20")).countedQuantity(new BigDecimal("18")).variance(new BigDecimal("-2"))
            .unit("tablet").reason("Synthetic physical count").recordedBy(actor).recordedByName("Synthetic Counter")
            .recordedAt(LocalDateTime.of(2026, 11, 26, 8, 0)).build();
        count.setId(UUID.randomUUID());
        when(roles.requireActiveHospitalId()).thenReturn(hospital.getId());
        when(roles.getCurrentUserId()).thenReturn(actor.getId());
        when(roles.hasRole(eq(actor.getId()), eq(hospital.getId()), anyString()))
            .thenAnswer(call -> "PHARMACIST".equals(call.getArgument(2)));
    }

    private StockCountRequestDTO request() {
        return new StockCountRequestDTO(item.getId(), null, count.getClientRequestId(), new BigDecimal("20"), "tablet", 0L, null, new BigDecimal("18"), count.getReason());
    }

    private void stock() {
        when(items.findById(item.getId())).thenReturn(Optional.of(item));
        when(items.tryLockById(item.getId())).thenReturn(Optional.of(item));
    }

    @Test
    void recordingCapturesTheObservationAndNeverMovesStock() {
        stock();
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(counts.findByHospitalIdAndClientRequestId(hospital.getId(), count.getClientRequestId()))
            .thenReturn(Optional.empty(), Optional.of(count));
        var saved = service.record(request());
        assertThat(saved.getBookQuantity()).isEqualByComparingTo("20");
        assertThat(saved.getCountedQuantity()).isEqualByComparingTo("18");
        assertThat(saved.getVariance()).isEqualByComparingTo("-2");
        assertThat(saved.getRecordedByName()).isEqualTo("Synthetic Counter");
        assertThat(saved.getAppliedAt()).isNull();
        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("20");
        verify(jdbc).update(contains("INSERT INTO clinical.pharmacy_stock_counts"), any(Object[].class));
        verifyNoInteractions(movements);
    }

    @Test
    void aRecordRetryReturnsTheSameObservationAndCannotChangeItsQuantity() {
        when(counts.findByHospitalIdAndClientRequestId(hospital.getId(), count.getClientRequestId())).thenReturn(Optional.of(count));
        assertThat(service.record(request()).getId()).isEqualTo(count.getId());
        var changed = new StockCountRequestDTO(item.getId(), null, count.getClientRequestId(), new BigDecimal("20"), "tablet", 0L, null, BigDecimal.TEN, count.getReason());
        assertThatThrownBy(() -> service.record(changed)).isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.requestReused");
        verifyNoInteractions(items, jdbc, movements);
    }

    @Test
    void noAdjustmentCanRunWithoutExplicitConfirmation() {
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        assertThatThrownBy(() -> service.confirm(count.getId(), new StockCountConfirmationDTO(false, null)))
            .isInstanceOf(FieldBusinessException.class).hasFieldOrPropertyWithValue("field", "confirmed");
        verifyNoInteractions(items, jdbc, movements);
    }

    @Test
    void aMovementDuringThePhysicalCountRequiresARecountBeforeRecordingAVariance() {
        stock();
        item.setQuantityOnHand(new BigDecimal("19"));
        assertThatThrownBy(() -> service.record(request()))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        verifyNoInteractions(jdbc, movements, users);
    }

    @Test
    void confirmationUsesTheExistingAdjustmentAndWitnessFlowExactlyOnce() {
        stock();
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        when(counts.save(count)).thenReturn(count);
        UUID movementId = UUID.randomUUID();
        UUID witness = UUID.randomUUID();
        when(movements.recordTransaction(any())).thenReturn(StockTransactionResponseDTO.builder().id(movementId).build());
        var confirmation = new StockCountConfirmationDTO(true, witness);
        assertThat(service.confirm(count.getId(), confirmation).getAdjustmentId()).isEqualTo(movementId);
        assertThat(count.getSamePersonConfirmation()).isTrue();
        assertThat(service.confirm(count.getId(), confirmation).getAdjustmentId()).isEqualTo(movementId);
        var movement = ArgumentCaptor.forClass(StockTransactionRequestDTO.class);
        verify(movements, times(1)).recordTransaction(movement.capture());
        assertThat(movement.getValue().getQuantity()).isEqualByComparingTo("-2");
        assertThat(movement.getValue().getWitnessedBy()).isEqualTo(witness);
        assertThat(movement.getValue().getReferenceId()).isEqualTo(count.getId());
        assertThat(count.getAppliedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 8, 0));
    }

    @Test
    void recorderCannotConfirmWhenAnotherEligibleColleagueExists() {
        stock();
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        User other = new User(); other.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(eq(hospital.getId()), any())).thenReturn(java.util.List.of(actor, other));
        assertThatThrownBy(() -> service.confirm(count.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.otherConfirmerRequired");
        verify(assignments).findLiveUsersAtHospitalWithRoleCodes(hospital.getId(), java.util.Set.of(
            "PHARMACIST", "ROLE_PHARMACIST", "INVENTORY_CLERK", "ROLE_INVENTORY_CLERK",
            "STORE_MANAGER", "ROLE_STORE_MANAGER", "HOSPITAL_ADMIN", "ROLE_HOSPITAL_ADMIN"));
        verifyNoInteractions(movements);
        verify(counts, never()).save(any());
    }

    @Test
    void anotherPersonCanConfirmAndTheResponseRecordsThatFact() {
        stock();
        User original = new User(); original.setId(UUID.randomUUID());
        count.setRecordedBy(original);
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        when(counts.save(count)).thenReturn(count);
        when(movements.recordTransaction(any())).thenReturn(StockTransactionResponseDTO.builder().id(UUID.randomUUID()).build());
        var result = service.confirm(count.getId(), new StockCountConfirmationDTO(true, null));
        assertThat(result.getSamePersonConfirmation()).isFalse();
        verifyNoInteractions(assignments);
    }

    @Test
    void stockOrUnitChangesRequireARecountRatherThanAnOutdatedAdjustment() {
        stock();
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        item.setQuantityOnHand(new BigDecimal("21"));
        assertThatThrownBy(() -> service.confirm(count.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        item.setQuantityOnHand(new BigDecimal("20"));
        item.setUnit("box");
        assertThatThrownBy(() -> service.confirm(count.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        assertThat(count.getAppliedAt()).isNull();
        verifyNoInteractions(movements);
        verify(counts, never()).save(any());
    }

    @Test
    void equalQuantityAndUnitAfterAnInterveningMovementStillRequireARecountAtBothSteps() {
        stock();
        item.setStockRevision(2L);
        assertThatThrownBy(() -> service.record(request()))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        assertThatThrownBy(() -> service.confirm(count.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        verifyNoInteractions(movements, jdbc, users);
    }

    // ── QA D4191 (r81): a count that will never be applied can be closed ──

    @Test
    void aWaitingCountIsClosedWithWhoAndWhy_andNothingMovesInTheStock() {
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(counts.save(count)).thenReturn(count);
        assertThat(StockCountService.statusOf(count)).isEqualTo("PENDING");

        var closed = service.close(count.getId(), new StockCountClosureDTO("  Comptage saisi par erreur  "));

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getCloseReason()).isEqualTo("Comptage saisi par erreur");
        assertThat(closed.getClosedByName()).isEqualTo("Synthetic Counter");
        assertThat(closed.getClosedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 8, 0));
        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("20");
        verifyNoInteractions(movements, jdbc);
        // Closing twice changes nothing; a closed count is never applied afterwards.
        assertThat(service.close(count.getId(), new StockCountClosureDTO("autre")).getCloseReason())
            .isEqualTo("Comptage saisi par erreur");
        verify(counts, times(1)).save(count);
        assertThatThrownBy(() -> service.confirm(count.getId(), new StockCountConfirmationDTO(true, null)))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.closed");
        verifyNoInteractions(movements);
    }

    @Test
    void closingNeedsAReason_andAnAppliedCountIsNeverClosed() {
        when(counts.findLocked(count.getId(), hospital.getId())).thenReturn(Optional.of(count));
        assertThatThrownBy(() -> service.close(count.getId(), new StockCountClosureDTO("   ")))
            .isInstanceOf(FieldBusinessException.class).hasFieldOrPropertyWithValue("field", "reason")
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.closeReasonRequired");
        count.setAppliedAt(LocalDateTime.of(2026, 11, 26, 7, 0));
        assertThatThrownBy(() -> service.close(count.getId(), new StockCountClosureDTO("Erreur")))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.alreadyApplied");
        assertThat(count.getClosedAt()).isNull();
        verify(counts, never()).save(any());
    }

    @Test
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    void aCountWhoseStockMovedSinceIsListedAsOutdated_notAsWaiting() {
        assertThat(StockCountService.statusOf(count)).isEqualTo("PENDING");
        item.setQuantityOnHand(new BigDecimal("19"));
        assertThat(StockCountService.statusOf(count)).isEqualTo("OUTDATED");
        item.setQuantityOnHand(new BigDecimal("20"));
        item.setStockRevision(3L);
        assertThat(StockCountService.statusOf(count)).isEqualTo("OUTDATED");
        count.setAppliedAt(LocalDateTime.of(2026, 11, 26, 7, 0));
        assertThat(StockCountService.statusOf(count)).isEqualTo("APPLIED");
    }

    @Test
    void aRecountClosesTheCountsOfTheSameStockThatStillWait_andARetryClosesNothing() {
        stock();
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        when(counts.findByHospitalIdAndClientRequestId(hospital.getId(), count.getClientRequestId()))
            .thenReturn(Optional.empty(), Optional.of(count));
        when(jdbc.update(contains("INSERT INTO clinical.pharmacy_stock_counts"), any(Object[].class))).thenReturn(1);

        service.record(request());

        verify(counts).closeOpenItemCounts(eq(hospital.getId()), eq(item.getId()), any(UUID.class),
            eq(LocalDateTime.of(2026, 11, 26, 8, 0)), eq(actor), eq("Synthetic Counter"),
            eq("Replaced by a new count of the same stock."));
        // The same request again: the observation exists, nothing is inserted, nothing is closed.
        clearInvocations(counts);
        when(counts.findByHospitalIdAndClientRequestId(hospital.getId(), count.getClientRequestId()))
            .thenReturn(Optional.of(count));
        service.record(request());
        verify(counts, never()).closeOpenItemCounts(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherHospitalsItemIsNotFoundAndIsNeverLocked() {
        when(items.findById(item.getId())).thenReturn(Optional.of(item));
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        item.getPharmacy().setHospital(other);
        assertThatThrownBy(() -> service.record(request())).isInstanceOf(ResourceNotFoundException.class);
        verify(items, never()).tryLockById(any());
        verifyNoInteractions(jdbc, movements);
    }
}
