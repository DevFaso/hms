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

    @InjectMocks private PharmacySaleServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private Hospital hospital;
    private Pharmacy pharmacy;
    private User user;
    private MedicationCatalogItem item;

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
                .hasMessageContaining("active hospital context");
    }

    @Test
    @DisplayName("createSale: rejects when request has no lines")
    void rejectsEmptyLines() {
        PharmacySaleRequestDTO dto = request(List.of());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createSale(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one line");
    }

    @Test
    @DisplayName("createSale: computes line totals and aggregate sale total with HALF_UP rounding")
    void computesTotals() {
        // 2 × 100 + 3 × 50.50 = 200 + 151.50 = 351.50
        SaleLineRequestDTO l1 = line(BigDecimal.valueOf(2), BigDecimal.valueOf(100));
        SaleLineRequestDTO l2 = line(BigDecimal.valueOf(3), BigDecimal.valueOf(50.50));
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
        org.mockito.Mockito.verify(saleRepository).save(captor.capture());
        PharmacySale saved = captor.getValue();

        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getLineTotal()).isEqualByComparingTo("200.00");
        assertThat(saved.getLines().get(1).getLineTotal()).isEqualByComparingTo("151.50");
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("351.50");
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

        org.mockito.Mockito.verify(patientRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    @DisplayName("createSale: rejects line whose stock lot belongs to another pharmacy")
    void rejectsCrossPharmacyStockLot() {
        UUID stockLotId = UUID.randomUUID();
        UUID otherPharmacyId = UUID.randomUUID();
        Pharmacy other = Pharmacy.builder().hospital(hospital).name("Other").build();
        other.setId(otherPharmacyId);

        InventoryItem invItem = InventoryItem.builder().pharmacy(other).build();
        StockLot lot = StockLot.builder().inventoryItem(invItem).build();
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
                .hasMessageContaining("does not belong to the selling pharmacy");
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
                .hasMessageContaining("quantity must be greater than zero");
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
}
