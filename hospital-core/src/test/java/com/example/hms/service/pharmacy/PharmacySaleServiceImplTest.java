package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacySaleMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleResponseDTO;
import com.example.hms.payload.dto.pharmacy.SaleLineRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-07 follow-up: unit tests for PharmacySaleServiceImpl. Covers the cases that
 * matter for safety: tenant isolation, line-item validation, line-total math,
 * stock-lot ownership, and authenticated-actor capture.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacySaleServiceImpl")
class PharmacySaleServiceImplTest {

    @Mock private PharmacySaleRepository saleRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRepository userRepository;
    @Mock private MedicationCatalogItemRepository catalogItemRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private PharmacySaleMapper mapper;
    @Mock private RoleValidator roleValidator;

    /** QA D964: FEFO is followed unless a test says otherwise (null = no override recorded). */
    @org.mockito.Mock private FefoLotPolicy fefoLotPolicy;
    /** QA D2671 / D2670 (r65): the list price and the stock of every line. */
    @Mock private com.example.hms.repository.pharmacy.InventoryItemRepository inventoryItemRepository;
    @Mock private com.example.hms.repository.pharmacy.StockTransactionRepository stockTransactionRepository;
    @Mock private com.example.hms.service.payment.PaymentReferenceRegistry referenceRegistry;
    @InjectMocks private PharmacySaleServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private Hospital hospital;
    private Pharmacy pharmacy;
    private User user;
    private MedicationCatalogItem item;
    /** The pharmacy's inventory item for {@link #item}: list price 10 F CFA, 1 000 on hand. */
    private InventoryItem stocked;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);

        pharmacy = Pharmacy.builder().hospital(hospital).name("Main").build();
        pharmacy.setId(pharmacyId);

        user = new User();
        user.setId(userId);

        item = MedicationCatalogItem.builder()
                .nameFr("Paracétamol")
                .hospital(hospital)
                .build();
        item.setId(itemId);

        stocked = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(item)
                .unitPrice(BigDecimal.TEN).quantityOnHand(new BigDecimal("1000")).active(true).build();
        stocked.setId(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, itemId))
                .thenReturn(Optional.of(stocked));
        org.mockito.Mockito.lenient().when(inventoryItemRepository.decrementOnHand(any(), any(), any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(stockLotRepository.decrementRemaining(any(), any(), any())).thenReturn(1);
    }

    private SaleLineRequestDTO line(BigDecimal qty, BigDecimal price) {
        return SaleLineRequestDTO.builder()
                .medicationCatalogItemId(itemId)
                .quantity(qty)
                .unitPrice(price)
                .build();
    }

    private PharmacySaleRequestDTO request(List<SaleLineRequestDTO> lines) {
        return PharmacySaleRequestDTO.builder()
                .pharmacyId(pharmacyId)
                .hospitalId(hospitalId)
                .paymentMethod(PharmacyPaymentMethod.CASH)
                .lines(lines)
                .build();
    }

    @Test
    @DisplayName("createSale: rejects when request hospital differs from active hospital context")
    void rejectsCrossTenantHospital() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.hospitalMismatch");
    }

    @Test
    @DisplayName("QA D4182: nothing is sold at the counter of a partner pharmacy")
    void d4182_aPartnerPharmacyHasNoCounterOfTheHospital() {
        pharmacy.setPharmacyType(com.example.hms.enums.PharmacyType.PARTNER_PHARMACY);
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("field", "messageKey").containsExactly("pharmacyId", PharmacyStores.NOT_OWN_STORE_KEY);
        verify(saleRepository, never()).save(any());
        verify(inventoryItemRepository, never()).decrementOnHand(any(), any(), any());
    }

    @Test
    @DisplayName("QA D1611: a counter sale paid by insurance is refused on paymentMethod")
    void d1611_insuranceIsNotAPayment() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        dto.setPaymentMethod(PharmacyPaymentMethod.INSURANCE);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("billing.payment.insuranceNotAPayment");
    }

    @Test
    @DisplayName("Billing plan PR 9: a Mobile Money counter sale without an operator is refused on mobileMoneyOperator")
    void mobileMoneyWithoutOperatorIsRefused() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        dto.setPaymentMethod(PharmacyPaymentMethod.MOBILE_MONEY);
        dto.setReferenceNumber("OM-R20-55124");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("field", "messageKey")
                .containsExactly("mobileMoneyOperator", "billing.payment.operatorRequired");
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D1129: a mobile-money sale whose reference is already recorded is refused")
    void d1129_aReusedReferenceIsRefused() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        dto.setPaymentMethod(PharmacyPaymentMethod.MOBILE_MONEY);
        dto.setMobileMoneyOperator(com.example.hms.enums.MobileMoneyOperator.ORANGE_MONEY);
        dto.setReferenceNumber("OM-R20-55123");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        doThrow(new BusinessException("billing.payment.duplicateReference"))
                .when(referenceRegistry).requireUnused(hospitalId, "OM-R20-55123", "referenceNumber");

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("billing.payment.duplicateReference");
        verify(saleRepository, never()).save(any());
    }

    @Test
    void d3948_aMobileMoneySaleNeedsAReferenceWithOrWithoutAnIdempotencyKey() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        for (boolean keyed : new boolean[]{false, true}) {
            for (String reference : new String[]{null, "   "}) {
                PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
                dto.setPaymentMethod(PharmacyPaymentMethod.MOBILE_MONEY);
                dto.setMobileMoneyOperator(com.example.hms.enums.MobileMoneyOperator.ORANGE_MONEY);
                dto.setReferenceNumber(reference);
                if (keyed) dto.setClientRequestId(UUID.randomUUID());

                assertThatThrownBy(() -> service.createSale(dto))
                    .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                    .extracting("field", "messageKey")
                    .containsExactly("referenceNumber", "pharmacy.payment.mobileMoneyReferenceRequired");
            }
        }
        verify(saleRepository, never()).save(any());
        verify(stockTransactionRepository, never()).save(any());
        verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
    }

    @Test
    @DisplayName("createSale: rejects when request has no lines")
    void rejectsEmptyLines() {
        PharmacySaleRequestDTO dto = request(List.of());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.linesRequired");
    }

    @Test
    @DisplayName("QA D2671: every line is priced at the list price, in whole francs, and the total is their sum")
    void computesTotals() {
        // 2 × 10 + 3 × 10 at the list price (no price sent on the second line).
        SaleLineRequestDTO l1 = line(BigDecimal.valueOf(2), BigDecimal.TEN);
        SaleLineRequestDTO l2 = line(BigDecimal.valueOf(3), null);
        PharmacySaleRequestDTO dto = request(List.of(l1, l2));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        // Echo back whatever the service builds so we can inspect totals.
        when(saleRepository.save(any(PharmacySale.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponseDTO(any(PharmacySale.class)))
                .thenReturn(PharmacySaleResponseDTO.builder().build());

        service.createSale(dto);

        ArgumentCaptor<PharmacySale> captor = ArgumentCaptor.forClass(PharmacySale.class);
        verify(saleRepository).save(captor.capture());
        PharmacySale saved = captor.getValue();

        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getUnitPrice()).isEqualByComparingTo("10");
        assertThat(saved.getLines().get(0).getLineTotal()).isEqualByComparingTo("20.00");
        assertThat(saved.getLines().get(1).getUnitPrice()).isEqualByComparingTo("10");
        assertThat(saved.getLines().get(1).getLineTotal()).isEqualByComparingTo("30.00");
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getCurrency()).isEqualTo("XOF");
        assertThat(saved.getSoldByUser()).isEqualTo(user);
    }

    @Test
    @DisplayName("createSale: anonymous walk-in (no patientId) is permitted")
    void allowsAnonymousWalkIn() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        // patientId left null
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(saleRepository.save(any(PharmacySale.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponseDTO(any(PharmacySale.class)))
                .thenReturn(PharmacySaleResponseDTO.builder().build());

        service.createSale(dto);

        verify(patientRepository, never()).findById(any());
    }

    @Test
    @DisplayName("createSale: rejects line whose stock lot belongs to another pharmacy")
    void rejectsCrossPharmacyStockLot() {
        UUID stockLotId = UUID.randomUUID();
        UUID otherPharmacyId = UUID.randomUUID();
        Pharmacy other = Pharmacy.builder().hospital(hospital).name("Other").build();
        other.setId(otherPharmacyId);

        InventoryItem invItem = InventoryItem.builder().pharmacy(other).build();
        StockLot lot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).build();
        lot.setId(stockLotId);

        SaleLineRequestDTO l = line(BigDecimal.ONE, BigDecimal.TEN);
        l.setStockLotId(stockLotId);
        PharmacySaleRequestDTO dto = request(List.of(l));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(lot));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.lotOtherPharmacy");
    }

    @Test
    @DisplayName("QA D779: a counter sale of 2.5 tablets from a counted lot is refused, nothing is saved")
    void rejectsAFractionOfACountedLot() {
        UUID stockLotId = UUID.randomUUID();
        InventoryItem invItem = stocked;
        stocked.setUnit("comprimé");
        StockLot lot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).build();
        lot.setId(stockLotId);
        SaleLineRequestDTO l = line(new BigDecimal("2.5"), BigDecimal.TEN);
        l.setStockLotId(stockLotId);
        PharmacySaleRequestDTO dto = request(List.of(l));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(lot));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .matches(e -> WholeUnitQuantity.DEFAULT_KEY.equals(((BusinessException) e).getMessageKey()));
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D964: a counter sale line from a later-dated lot with no reason is refused, nothing is saved")
    void rejectsALineSkippingTheShorterDatedLot() {
        UUID stockLotId = UUID.randomUUID();
        InventoryItem invItem = stocked;
        StockLot lot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).build();
        lot.setId(stockLotId);
        SaleLineRequestDTO l = line(BigDecimal.ONE, BigDecimal.TEN);
        l.setStockLotId(stockLotId);
        PharmacySaleRequestDTO dto = request(List.of(l));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(lot));
        when(fefoLotPolicy.requireFefoOrReason(lot, null, java.util.Map.of(stockLotId, BigDecimal.ONE)))
                .thenThrow(new BusinessException("pharmacy.fefo.reasonRequired", "LOT-COURT", "1 déc. 2026"));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.fefo.reasonRequired");
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("review of #975: the lines of a sale together never take more than the lot holds")
    void linesTakingMoreThanTheLotHoldsAreRefused() {
        InventoryItem invItem = stocked;
        StockLot lot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).lotNumber("R16-PCM-COURT")
                .remainingQuantity(BigDecimal.TEN).build();
        lot.setId(UUID.randomUUID());
        SaleLineRequestDTO first = line(new BigDecimal("6"), null);
        first.setStockLotId(lot.getId());
        SaleLineRequestDTO second = line(new BigDecimal("5"), null);
        second.setStockLotId(lot.getId());
        PharmacySaleRequestDTO dto = request(List.of(first, second));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("stock.lot.insufficient");
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("review of #975: each line is judged on what the other lines of the same sale take, in any order")
    void aSaleSplitAcrossLotsTellsTheRuleWhatTheOtherLinesTake() {
        InventoryItem invItem = stocked;
        StockLot shortLot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).build();
        shortLot.setId(UUID.randomUUID());
        StockLot longLot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).build();
        longLot.setId(UUID.randomUUID());
        // Entered long lot first: the order of the lines does not matter.
        SaleLineRequestDTO fromLong = line(new BigDecimal("5"), null);
        fromLong.setStockLotId(longLot.getId());
        SaleLineRequestDTO fromShort = line(BigDecimal.TEN, null);
        fromShort.setStockLotId(shortLot.getId());
        PharmacySaleRequestDTO dto = request(List.of(fromLong, fromShort));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockLotRepository.findById(shortLot.getId())).thenReturn(Optional.of(shortLot));
        when(stockLotRepository.findById(longLot.getId())).thenReturn(Optional.of(longLot));
        when(saleRepository.save(any(PharmacySale.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createSale(dto);

        java.util.Map<UUID, BigDecimal> wholeSale =
                java.util.Map.of(shortLot.getId(), BigDecimal.TEN, longLot.getId(), new BigDecimal("5"));
        verify(fefoLotPolicy).requireFefoOrReason(longLot, null, wholeSale);
        verify(fefoLotPolicy).requireFefoOrReason(shortLot, null, wholeSale);
    }

    @Test
    @DisplayName("review of #975: a FEFO reason that overflows the line's 500-character notes is refused with the key, not a constraint violation")
    void aFefoReasonThatOverflowsTheLineNotesIsRefused() {
        UUID stockLotId = UUID.randomUUID();
        InventoryItem invItem = stocked;
        StockLot lot = StockLot.builder().inventoryItem(invItem).expiryDate(java.time.LocalDate.now().plusYears(1)).build();
        lot.setId(stockLotId);
        SaleLineRequestDTO l = line(BigDecimal.ONE, BigDecimal.TEN);
        l.setStockLotId(stockLotId);
        l.setNotes("n".repeat(400));
        l.setFefoOverrideReason("r".repeat(150));
        PharmacySaleRequestDTO dto = request(List.of(l));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(lot));
        when(fefoLotPolicy.requireFefoOrReason(lot, "r".repeat(150), java.util.Map.of(stockLotId, BigDecimal.ONE))).thenReturn("r".repeat(150));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.fefo.reasonTooLong");
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("createSale: rejects negative quantity")
    void rejectsNegativeQuantity() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ZERO, BigDecimal.TEN)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.lineQuantityPositive");
    }

    @Test
    @DisplayName("getSale: returns not-found when the sale belongs to another hospital")
    void getSaleHidesCrossTenantExistence() {
        UUID id = UUID.randomUUID();
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        PharmacySale sale = PharmacySale.builder().hospital(otherHospital).build();
        sale.setId(id);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(saleRepository.findById(id)).thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.getSale(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /* PR11-idempotency */
    @Test
    @DisplayName("PR 11 (QA D2430): a keyed sale locks its pharmacy, stores the key, and is flushed")
    void d2430_aKeyedSaleStoresItsKey() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        UUID key = UUID.randomUUID();
        dto.setClientRequestId(key);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(saleRepository.saveAndFlush(any(PharmacySale.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponseDTO(any(PharmacySale.class))).thenReturn(PharmacySaleResponseDTO.builder().build());

        service.createSale(dto);

        ArgumentCaptor<PharmacySale> captor = ArgumentCaptor.forClass(PharmacySale.class);
        verify(saleRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isEqualTo(key);
        verify(pharmacyRepository, never()).findByIdAndHospital_Id(any(), any());
    }

    @Test
    @DisplayName("PR 11 (QA D2430): a sale retried with its key answers with the first and records nothing")
    void d2430_aRetriedSaleIsAnsweredAgain() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        UUID key = UUID.randomUUID();
        dto.setClientRequestId(key);
        PharmacySale first = PharmacySale.builder().pharmacy(pharmacy).hospital(hospital)
                .paymentMethod(PharmacyPaymentMethod.CASH).clientRequestId(key).totalAmount(BigDecimal.TEN).build();
        first.addLine(com.example.hms.model.pharmacy.SaleLine.builder().medicationCatalogItem(item)
                .quantity(BigDecimal.ONE).build());
        PharmacySaleResponseDTO answer = PharmacySaleResponseDTO.builder().build();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(saleRepository.findByHospital_IdAndClientRequestId(hospitalId, key)).thenReturn(Optional.of(first));
        when(mapper.toResponseDTO(first)).thenReturn(answer);

        assertThat(service.createSale(dto)).isSameAs(answer);

        verify(saleRepository, never()).save(any());
        verify(saleRepository, never()).saveAndFlush(any());
        verify(stockLotRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D3402 (r70): the same key with a different basket (3 instead of 1) is refused 409, never answered")
    void d3402_theSameKeyWithAnotherBasketIsRefused() {
        PharmacySaleRequestDTO dto = request(List.of(line(new BigDecimal("3"), BigDecimal.TEN)));
        UUID key = UUID.randomUUID();
        dto.setClientRequestId(key);
        PharmacySale first = PharmacySale.builder().pharmacy(pharmacy).hospital(hospital)
                .paymentMethod(PharmacyPaymentMethod.CASH).clientRequestId(key).totalAmount(BigDecimal.TEN).build();
        first.addLine(com.example.hms.model.pharmacy.SaleLine.builder().medicationCatalogItem(item)
                .quantity(BigDecimal.ONE).build());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(saleRepository.findByHospital_IdAndClientRequestId(hospitalId, key)).thenReturn(Optional.of(first));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(saleRepository, never()).saveAndFlush(any());
        verify(stockLotRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D3402 (r70): the same key naming another medication is refused 409")
    void d3402_theSameKeyWithAnotherMedicationIsRefused() {
        SaleLineRequestDTO other = line(BigDecimal.ONE, BigDecimal.TEN);
        other.setMedicationCatalogItemId(UUID.randomUUID());
        PharmacySaleRequestDTO dto = request(List.of(other));
        UUID key = UUID.randomUUID();
        dto.setClientRequestId(key);
        PharmacySale first = PharmacySale.builder().pharmacy(pharmacy).hospital(hospital)
                .paymentMethod(PharmacyPaymentMethod.CASH).clientRequestId(key).totalAmount(BigDecimal.TEN).build();
        first.addLine(com.example.hms.model.pharmacy.SaleLine.builder().medicationCatalogItem(item)
                .quantity(BigDecimal.ONE).build());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(saleRepository.findByHospital_IdAndClientRequestId(hospitalId, key)).thenReturn(Optional.of(first));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
    }

    @Test
    @DisplayName("PR 11 (QA D2430): the key of another sale (other method) is refused, never answered")
    void d2430_theKeyOfAnotherSaleIsRefused() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        UUID key = UUID.randomUUID();
        dto.setClientRequestId(key);
        PharmacySale first = PharmacySale.builder().pharmacy(pharmacy).hospital(hospital)
                .paymentMethod(PharmacyPaymentMethod.MOBILE_MONEY).clientRequestId(key).totalAmount(BigDecimal.TEN).build();
        first.addLine(com.example.hms.model.pharmacy.SaleLine.builder().medicationCatalogItem(item)
                .quantity(BigDecimal.ONE).build());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(saleRepository.findByHospital_IdAndClientRequestId(hospitalId, key)).thenReturn(Optional.of(first));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(saleRepository, never()).saveAndFlush(any());
    }

    /* PR11-idempotency-mm */
    @Test
    @DisplayName("PR 11 (QA D2430): a Mobile Money sale retried with its key is answered, not refused as a reused reference")
    void d2430_aRetriedMobileMoneySaleIsNotARefusedReference() {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        dto.setPaymentMethod(PharmacyPaymentMethod.MOBILE_MONEY);
        dto.setMobileMoneyOperator(com.example.hms.enums.MobileMoneyOperator.ORANGE_MONEY);
        dto.setReferenceNumber("OM-R64-0001");
        UUID key = UUID.randomUUID();
        dto.setClientRequestId(key);
        PharmacySale first = PharmacySale.builder().pharmacy(pharmacy).hospital(hospital)
                .paymentMethod(PharmacyPaymentMethod.MOBILE_MONEY).clientRequestId(key).totalAmount(BigDecimal.TEN).build();
        first.addLine(com.example.hms.model.pharmacy.SaleLine.builder().medicationCatalogItem(item)
                .quantity(BigDecimal.ONE).build());
        PharmacySaleResponseDTO answer = PharmacySaleResponseDTO.builder().build();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        when(saleRepository.findByHospital_IdAndClientRequestId(hospitalId, key)).thenReturn(Optional.of(first));
        when(mapper.toResponseDTO(first)).thenReturn(answer);

        assertThat(service.createSale(dto)).isSameAs(answer);
        verify(referenceRegistry, never()).requireUnused(any(), any(), any());
    }

    /* PR11-idempotency-race */
    private PharmacySaleRequestDTO keyedNewSale(UUID key) {
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
        dto.setClientRequestId(key);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findLockedByIdAndHospitalId(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        org.mockito.Mockito.lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        org.mockito.Mockito.lenient().when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        return dto;
    }

    @Test
    @DisplayName("PR 11 (QA D2430): a key another sale took meanwhile (V310 index) is a 409, any other violation is not hidden")
    void d2430_aKeyTakenMeanwhileIsAConflict() {
        PharmacySaleRequestDTO dto = keyedNewSale(UUID.randomUUID());
        when(saleRepository.saveAndFlush(any(PharmacySale.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uk_psale_hospital_client_request\""))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("fk_psale_patient"));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("PR 11: a new keyed Mobile Money sale still checks its reference")
    void d2430_aNewKeyedMobileMoneySaleChecksItsReference() {
        PharmacySaleRequestDTO dto = keyedNewSale(UUID.randomUUID());
        dto.setPaymentMethod(PharmacyPaymentMethod.MOBILE_MONEY);
        dto.setMobileMoneyOperator(com.example.hms.enums.MobileMoneyOperator.ORANGE_MONEY);
        dto.setReferenceNumber("OM-R64-0002");
        doThrow(new BusinessException("billing.payment.duplicateReference"))
                .when(referenceRegistry).requireUnused(hospitalId, "OM-R64-0002", "referenceNumber");

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("billing.payment.duplicateReference");
        verify(saleRepository, never()).saveAndFlush(any());
    }

    /* r65-pharmacy-money-stock */
    private void stubNewUnkeyedSale() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(pharmacy));
        org.mockito.Mockito.lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(catalogItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        org.mockito.Mockito.lenient().when(saleRepository.save(any(PharmacySale.class))).thenAnswer(inv -> {
            PharmacySale sale = inv.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(UUID.randomUUID());
            }
            return sale;
        });
    }

    private StockLot lotOfStockedItem(String number, String remaining) {
        StockLot lot = StockLot.builder().inventoryItem(stocked).expiryDate(java.time.LocalDate.now().plusYears(1)).lotNumber(number)
                .remainingQuantity(new BigDecimal(remaining)).build();
        lot.setId(UUID.randomUUID());
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
        return lot;
    }

    @Test
    @DisplayName("review of #1239: an expired lot is never sold (expired ON its expiry date, as for a dispense); nothing is saved or moved")
    void anExpiredLotIsNeverSold() {
        stubNewUnkeyedSale();
        StockLot lot = lotOfStockedItem("LOT-PERIME", "400");
        SaleLineRequestDTO l = line(BigDecimal.TEN, null);
        l.setStockLotId(lot.getId());
        PharmacySaleRequestDTO dto = request(List.of(l));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.systemDefault());

        for (java.time.LocalDate expiry : List.of(today, today.minusDays(1), today.minusYears(1))) {
            lot.setExpiryDate(expiry);
            assertThatThrownBy(() -> service.createSale(dto))
                    .as("expiry " + expiry)
                    .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                    .extracting("field", "messageKey")
                    .containsExactly("stockLotId", "pharmacy.sale.lotExpired");
        }
        verify(saleRepository, never()).save(any());
        verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
        verify(stockTransactionRepository, never()).save(any());

        // The day before its expiry date the lot is still sold.
        lot.setExpiryDate(today.plusDays(1));
        service.createSale(dto);
        verify(saleRepository).save(any(PharmacySale.class));
    }

    @Test
    @DisplayName("billing review of #1239: every sale line and the sale total are whole francs, whatever the unit price (a till counts no centimes)")
    void saleLinesAndTotalsAreWholeFrancs() {
        stubNewUnkeyedSale();
        // A box of 3 at 250 F converted to units: 83.33 F a unit.
        stocked.setUnitPrice(new BigDecimal("83.33"));
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, null), line(BigDecimal.valueOf(2), null)));

        service.createSale(dto);

        ArgumentCaptor<PharmacySale> captor = ArgumentCaptor.forClass(PharmacySale.class);
        verify(saleRepository).save(captor.capture());
        PharmacySale saved = captor.getValue();
        assertThat(saved.getLines()).extracting(com.example.hms.model.pharmacy.SaleLine::getLineTotal)
                .allSatisfy(total -> assertThat(total.remainder(BigDecimal.ONE)).isEqualByComparingTo("0"));
        assertThat(saved.getLines().get(0).getLineTotal()).isEqualByComparingTo("83");
        assertThat(saved.getLines().get(1).getLineTotal()).isEqualByComparingTo("167");
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("250");
        assertThat(saved.getTotalAmount().remainder(BigDecimal.ONE)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("QA D2671: a price the client sends that is not the list price (1 F CFA for 10) is refused, nothing is saved")
    void d2671_aClientPriceBelowTheListIsRefused() {
        stubNewUnkeyedSale();
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.TEN, BigDecimal.ONE)));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("field", "messageKey")
                .containsExactly("unitPrice", "pharmacy.sale.priceNotList");
        verify(saleRepository, never()).save(any());
        verify(stockTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D2671: a medication the pharmacy has not priced cannot be sold, whatever price is sent")
    void d2671_anUnpricedMedicationIsRefused() {
        stubNewUnkeyedSale();
        stocked.setUnitPrice(null);
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, new BigDecimal("250"))));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.noListPrice");
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D2671: a medication not stocked at the selling pharmacy cannot be sold")
    void d2671_aMedicationNotStockedHereIsRefused() {
        stubNewUnkeyedSale();
        when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, itemId))
                .thenReturn(Optional.empty());
        PharmacySaleRequestDTO dto = request(List.of(line(BigDecimal.ONE, null)));

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.notStocked");
    }

    @Test
    @DisplayName("QA D2670: a sale from a lot takes the lot and the item down and writes a SALE movement naming the sale")
    void d2670_aSaleMovesTheStockOfItsLot() {
        stubNewUnkeyedSale();
        StockLot lot = lotOfStockedItem("LOT-A-R65-1", "400");
        SaleLineRequestDTO l = line(BigDecimal.TEN, null);
        l.setStockLotId(lot.getId());

        service.createSale(request(List.of(l)));

        verify(stockLotRepository).decrementRemaining(org.mockito.ArgumentMatchers.eq(lot.getId()),
                org.mockito.ArgumentMatchers.argThat(q -> q.compareTo(BigDecimal.TEN) == 0), any());
        verify(inventoryItemRepository).decrementOnHand(org.mockito.ArgumentMatchers.eq(stocked.getId()),
                org.mockito.ArgumentMatchers.argThat(q -> q.compareTo(BigDecimal.TEN) == 0), any());
        ArgumentCaptor<com.example.hms.model.pharmacy.StockTransaction> tx =
                ArgumentCaptor.forClass(com.example.hms.model.pharmacy.StockTransaction.class);
        verify(stockTransactionRepository).save(tx.capture());
        ArgumentCaptor<PharmacySale> sale = ArgumentCaptor.forClass(PharmacySale.class);
        verify(saleRepository).save(sale.capture());
        assertThat(tx.getValue().getTransactionType()).isEqualTo(com.example.hms.enums.StockTransactionType.SALE);
        assertThat(tx.getValue().getStockLot()).isSameAs(lot);
        assertThat(tx.getValue().getInventoryItem()).isSameAs(stocked);
        assertThat(tx.getValue().getQuantity()).isEqualByComparingTo("10");
        assertThat(tx.getValue().getReferenceId()).isEqualTo(sale.getValue().getId());
        assertThat(tx.getValue().getPerformedByUser()).isSameAs(user);
    }

    @Test
    @DisplayName("QA D2670: a lot emptied meanwhile (atomic UPDATE takes 0 rows) refuses the sale with the lot named")
    void d2670_aLotEmptiedMeanwhileRefusesTheSale() {
        stubNewUnkeyedSale();
        StockLot lot = lotOfStockedItem("LOT-A-R65-1", "400");
        SaleLineRequestDTO l = line(BigDecimal.TEN, null);
        l.setStockLotId(lot.getId());
        when(stockLotRepository.decrementRemaining(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.createSale(request(List.of(l))))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("stock.lot.insufficient");
        verify(stockTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D2670: an item kept in lots is sold from a lot; a line with none is refused")
    void d2670_anItemKeptInLotsNeedsALot() {
        stubNewUnkeyedSale();
        when(stockLotRepository.existsByInventoryItemId(stocked.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.createSale(request(List.of(line(BigDecimal.ONE, null)))))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("field", "messageKey").containsExactly("stockLotId", "pharmacy.sale.lotRequired");
        verify(saleRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D2670: a lot of another medication of the same pharmacy is refused")
    void d2670_aLotOfAnotherMedicationIsRefused() {
        stubNewUnkeyedSale();
        InventoryItem otherDrug = InventoryItem.builder().pharmacy(pharmacy).build();
        otherDrug.setId(UUID.randomUUID());
        StockLot lot = StockLot.builder().inventoryItem(otherDrug).lotNumber("AMOX-1").build();
        lot.setId(UUID.randomUUID());
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
        SaleLineRequestDTO l = line(BigDecimal.ONE, null);
        l.setStockLotId(lot.getId());

        assertThatThrownBy(() -> service.createSale(request(List.of(l))))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.lotOtherMedication");
    }

    @Test
    @DisplayName("QA D2670: a controlled substance is never sold over the counter")
    void d2670_aControlledSubstanceIsRefused() {
        stubNewUnkeyedSale();
        item.setControlled(true);

        assertThatThrownBy(() -> service.createSale(request(List.of(line(BigDecimal.ONE, null)))))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.sale.controlledNeedsPrescription");
    }

    @Test
    @DisplayName("QA D2674: 2 packs of an item counted in tablets, 10 to a pack, sell and move 20 tablets at the tablet price")
    void d2674_aSaleInPacksIsConvertedToUnits() {
        stubNewUnkeyedSale();
        stocked.setUnit("comprimé");
        stocked.setUnitsPerPack(10);
        StockLot lot = lotOfStockedItem("LOT-A-R65-1", "400");
        SaleLineRequestDTO l = line(new BigDecimal("2"), null);
        l.setInPacks(true);
        l.setStockLotId(lot.getId());

        service.createSale(request(List.of(l)));

        ArgumentCaptor<PharmacySale> sale = ArgumentCaptor.forClass(PharmacySale.class);
        verify(saleRepository).save(sale.capture());
        assertThat(sale.getValue().getLines().get(0).getQuantity()).isEqualByComparingTo("20");
        assertThat(sale.getValue().getTotalAmount()).isEqualByComparingTo("200");
        verify(stockLotRepository).decrementRemaining(org.mockito.ArgumentMatchers.eq(lot.getId()),
                org.mockito.ArgumentMatchers.argThat(q -> q.compareTo(new BigDecimal("20")) == 0), any());
    }

    @Test
    @DisplayName("QA D2674: a quantity in packs of an item with no pack size is refused")
    void d2674_packsWithoutAPackSizeAreRefused() {
        stubNewUnkeyedSale();
        SaleLineRequestDTO l = line(BigDecimal.ONE, null);
        l.setInPacks(true);

        assertThatThrownBy(() -> service.createSale(request(List.of(l))))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.pack.noPackSize");
    }
}
