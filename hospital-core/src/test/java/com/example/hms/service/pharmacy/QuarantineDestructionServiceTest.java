package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.QuarantineDestructionRequestDTO;
import com.example.hms.payload.dto.pharmacy.QuarantineLineDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3415 (r70): the quarantine of a pharmacy and the witnessed destruction of what it holds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuarantineDestructionServiceTest {

    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private StockTransactionMapper stockTransactionMapper;
    @Mock private PharmacyServiceSupport support;
    @Mock private RoleValidator roleValidator;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;

    private QuarantineDestructionService service;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID witnessId = UUID.randomUUID();
    private Pharmacy pharmacy;
    private InventoryItem morphine;
    private StockLot lot;
    private User pharmacist;
    private User witness;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        service = new QuarantineDestructionService(pharmacyRepository, inventoryItemRepository, stockLotRepository,
                stockTransactionRepository, stockTransactionMapper,
                new ControlledStockWitness(assignmentRepository, userRepository), support, roleValidator);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        pharmacy = Pharmacy.builder().hospital(hospital).name("Main").build();
        pharmacy.setId(UUID.randomUUID());
        MedicationCatalogItem med = new MedicationCatalogItem();
        med.setId(UUID.randomUUID());
        med.setNameFr("Morphine 10 mg/mL");
        med.setStrength("10");
        med.setStrengthUnit("mg/mL");
        med.setControlled(true);
        morphine = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(med).unit("ampoule").build();
        morphine.setId(UUID.randomUUID());
        lot = StockLot.builder().inventoryItem(morphine).lotNumber("LOT-MOR-1").build();
        lot.setId(UUID.randomUUID());
        pharmacist = new User();
        pharmacist.setId(UUID.randomUUID());
        witness = new User();
        witness.setId(witnessId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(pharmacyRepository.findById(pharmacy.getId())).thenReturn(Optional.of(pharmacy));
        when(inventoryItemRepository.findById(morphine.getId())).thenReturn(Optional.of(morphine));
        when(inventoryItemRepository.lockById(morphine.getId())).thenReturn(Optional.of(morphine));
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
        when(support.resolveCurrentUser()).thenReturn(pharmacist);
        when(assignmentRepository.existsActiveWitness(witnessId, hospitalId, ControlledStockWitness.WITNESS_ROLE_CODES))
                .thenReturn(true);
        when(userRepository.findById(witnessId)).thenReturn(Optional.of(witness));
        when(stockTransactionRepository.save(any(StockTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        holds("3", "1");
    }

    /** The lot's quarantine: {@code in} quarantined, {@code out} destroyed. */
    private void holds(String in, String out) {
        when(stockTransactionRepository.sumQuantityForLot(lot.getId(), EnumSet.of(StockTransactionType.QUARANTINE)))
                .thenReturn(new BigDecimal(in));
        when(stockTransactionRepository.sumQuantityForLot(lot.getId(), EnumSet.of(StockTransactionType.DESTRUCTION)))
                .thenReturn(new BigDecimal(out));
    }

    private QuarantineDestructionRequestDTO destroy(String quantity, UUID by) {
        return QuarantineDestructionRequestDTO.builder().inventoryItemId(morphine.getId()).stockLotId(lot.getId())
                .quantity(new BigDecimal(quantity)).reason("Incinéré par le district").witnessedBy(by).build();
    }

    @Test
    @DisplayName("a witnessed destruction within what the quarantine holds: one DESTRUCTION row, the lot untouched")
    void destroysWithinTheQuarantine() {
        service.destroy(destroy("2", witnessId));

        ArgumentCaptor<StockTransaction> row = ArgumentCaptor.forClass(StockTransaction.class);
        verify(stockTransactionRepository).save(row.capture());
        assertThat(row.getValue().getTransactionType()).isEqualTo(StockTransactionType.DESTRUCTION);
        assertThat(row.getValue().getQuantity()).isEqualByComparingTo("2");
        assertThat(row.getValue().getWitnessedByUser()).isSameAs(witness);
        assertThat(row.getValue().getPerformedByUser()).isSameAs(pharmacist);
        assertThat(row.getValue().getReason()).isEqualTo("Destroyed: Incinéré par le district");
        verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
        verify(inventoryItemRepository, never()).decrementOnHand(any(), any(), any());
        // The register's saleable balance does not move; the quarantine does.
        assertThat(ControlledDrugRegisterService.signed(StockTransactionType.DESTRUCTION, BigDecimal.TWO))
                .isEqualByComparingTo("0");
        assertThat(QuarantineDestructionService.quarantineSigned(StockTransactionType.DESTRUCTION, BigDecimal.TWO))
                .isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("never more than the quarantine holds")
    void neverAboveTheQuarantine() {
        assertThatThrownBy(() -> service.destroy(destroy("3", witnessId)))
                .isInstanceOf(FieldBusinessException.class)
                .hasFieldOrPropertyWithValue("field", "quantity")
                .hasFieldOrPropertyWithValue("messageKey", "pharmacy.quarantine.aboveHeld");
        verify(stockTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a controlled drug is destroyed only in front of a witness, never the performer")
    void aControlledDestructionNeedsAWitness() {
        assertThatThrownBy(() -> service.destroy(destroy("1", null)))
                .isInstanceOf(FieldBusinessException.class).hasFieldOrPropertyWithValue("field", "witnessedBy");
        assertThatThrownBy(() -> service.destroy(destroy("1", pharmacist.getId())))
                .isInstanceOf(FieldBusinessException.class).hasFieldOrPropertyWithValue("field", "witnessedBy");
        verify(stockTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("an empty request names its fields together")
    void emptyRequestNamesItsFields() {
        assertThatThrownBy(() -> service.destroy(new QuarantineDestructionRequestDTO()))
                .isInstanceOf(BusinessException.class);
        verify(stockTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("tenancy: another hospital's item is the same 404 as a missing one")
    void anotherHospitalsItemIsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.destroy(destroy("1", witnessId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(pharmacy.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(stockTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("the list: quarantined less destroyed, per item and lot, empty lines left out")
    void listsWhatIsHeld() {
        UUID otherLot = UUID.randomUUID();
        when(stockTransactionRepository.sumByItemLotAndTypeForPharmacy(pharmacy.getId(),
                QuarantineDestructionService.QUARANTINE_TYPES)).thenReturn(List.of(
                new Object[] {morphine.getId(), lot.getId(), StockTransactionType.QUARANTINE, new BigDecimal("3")},
                new Object[] {morphine.getId(), lot.getId(), StockTransactionType.DESTRUCTION, new BigDecimal("1")},
                new Object[] {morphine.getId(), otherLot, StockTransactionType.QUARANTINE, new BigDecimal("1")},
                new Object[] {morphine.getId(), otherLot, StockTransactionType.DESTRUCTION, new BigDecimal("1")}));
        when(inventoryItemRepository.findAllWithCatalogItemByPharmacyId(pharmacy.getId())).thenReturn(List.of(morphine));

        List<QuarantineLineDTO> lines = service.list(pharmacy.getId());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).heldQuantity()).isEqualByComparingTo("2");
        assertThat(lines.get(0).lotNumber()).isEqualTo("LOT-MOR-1");
        assertThat(lines.get(0).controlled()).isTrue();
        // QA D2682: the name already carries its strength.
        assertThat(lines.get(0).medicationName()).isEqualTo("Morphine 10 mg/mL");
    }
}
