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

@ExtendWith(MockitoExtension.class)
class StockCountServiceTest {
    @Mock private StockCountRepository counts;
    @Mock private InventoryItemRepository items;
    @Mock private StockLotRepository lots;
    @Mock private PharmacyRepository pharmacies;
    @Mock private UserRepository users;
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
        service = new StockCountService(counts, items, lots, pharmacies, users, movements, roles, entityManager, jdbc,
            Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC));
        hospital = new Hospital(); hospital.setId(UUID.randomUUID());
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).active(true).build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().pharmacy(pharmacy).unit("tablet").quantityOnHand(new BigDecimal("20")).build();
        item.setId(UUID.randomUUID());
        actor = new User(); actor.setId(UUID.randomUUID()); actor.setFirstName("Synthetic"); actor.setLastName("Counter");
        count = StockCount.builder().hospital(hospital).inventoryItem(item).clientRequestId(UUID.randomUUID())
            .bookQuantity(new BigDecimal("20")).countedQuantity(new BigDecimal("18")).variance(new BigDecimal("-2"))
            .unit("tablet").reason("Synthetic physical count").recordedBy(actor).recordedByName("Synthetic Counter")
            .recordedAt(LocalDateTime.of(2026, 11, 26, 8, 0)).build();
        count.setId(UUID.randomUUID());
        when(roles.requireActiveHospitalId()).thenReturn(hospital.getId());
        when(roles.getCurrentUserId()).thenReturn(actor.getId());
        when(roles.hasRole(actor.getId(), hospital.getId(), "PHARMACIST")).thenReturn(true);
    }

    private StockCountRequestDTO request() {
        return new StockCountRequestDTO(item.getId(), null, count.getClientRequestId(), new BigDecimal("20"), "tablet", new BigDecimal("18"), count.getReason());
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
        assertThat(saved.bookQuantity()).isEqualByComparingTo("20");
        assertThat(saved.countedQuantity()).isEqualByComparingTo("18");
        assertThat(saved.variance()).isEqualByComparingTo("-2");
        assertThat(saved.recordedByName()).isEqualTo("Synthetic Counter");
        assertThat(saved.appliedAt()).isNull();
        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("20");
        verify(jdbc).update(contains("INSERT INTO clinical.pharmacy_stock_counts"), any(Object[].class));
        verifyNoInteractions(movements);
    }

    @Test
    void aRecordRetryReturnsTheSameObservationAndCannotChangeItsQuantity() {
        when(counts.findByHospitalIdAndClientRequestId(hospital.getId(), count.getClientRequestId())).thenReturn(Optional.of(count));
        assertThat(service.record(request()).id()).isEqualTo(count.getId());
        var changed = new StockCountRequestDTO(item.getId(), null, count.getClientRequestId(), new BigDecimal("20"), "tablet", BigDecimal.TEN, count.getReason());
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
        assertThat(service.confirm(count.getId(), confirmation).adjustmentId()).isEqualTo(movementId);
        assertThat(service.confirm(count.getId(), confirmation).adjustmentId()).isEqualTo(movementId);
        var movement = ArgumentCaptor.forClass(StockTransactionRequestDTO.class);
        verify(movements, times(1)).recordTransaction(movement.capture());
        assertThat(movement.getValue().getQuantity()).isEqualByComparingTo("-2");
        assertThat(movement.getValue().getWitnessedBy()).isEqualTo(witness);
        assertThat(movement.getValue().getReferenceId()).isEqualTo(count.getId());
        assertThat(count.getAppliedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 8, 0));
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
