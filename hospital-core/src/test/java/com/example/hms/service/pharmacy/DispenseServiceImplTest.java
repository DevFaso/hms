package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispenseServiceImplTest {

    @Mock private DispenseRepository dispenseRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private MedicationCatalogItemRepository medicationCatalogItemRepository;
    @Mock private DispenseMapper dispenseMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private PharmacyServiceSupport support;

    @InjectMocks
    private DispenseServiceImpl service;

    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID dispenseId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID stockLotId = UUID.randomUUID();

    private Hospital hospital;
    private Pharmacy pharmacy;
    private Prescription prescription;
    private Patient patient;
    private User user;
    private StockLot stockLot;
    private InventoryItem inventoryItem;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);

        pharmacy = Pharmacy.builder().hospital(hospital).name("Main Pharmacy").build();
        pharmacy.setId(pharmacyId);

        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setMedicationName("Amoxicillin");
        prescription.setQuantity(BigDecimal.TEN);

        patient = new Patient();
        patient.setId(patientId);
        prescription.setHospital(hospital);
        prescription.setPatient(patient);

        user = new User();
        user.setId(userId);

        inventoryItem = InventoryItem.builder()
                .pharmacy(pharmacy)
                .quantityOnHand(BigDecimal.valueOf(100))
                .build();
        inventoryItem.setId(UUID.randomUUID());

        stockLot = StockLot.builder()
                .inventoryItem(inventoryItem)
                .remainingQuantity(BigDecimal.valueOf(50))
                .build();
        stockLot.setId(stockLotId);
    }

    private DispenseRequestDTO buildRequest() {
        return DispenseRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .patientId(patientId)
                .pharmacyId(pharmacyId)
                .dispensedBy(userId)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .build();
    }

    private Dispense buildDispense(DispenseStatus status) {
        Dispense d = Dispense.builder()
                .prescription(prescription)
                .patient(patient)
                .pharmacy(pharmacy)
                .dispensedByUser(user)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .status(status)
                .build();
        d.setId(dispenseId);
        return d;
    }

    @Nested
    @DisplayName("createDispense")
    class CreateDispense {

        @Test
        @DisplayName("should dispense fully without stock lot")
        void shouldDispenseFullyWithoutStockLot() {
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any(Prescription.class))).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            DispenseResponseDTO result = service.createDispense(dto);

            assertThat(result.getMedicationName()).isEqualTo("Amoxicillin");
            verify(dispenseRepository).save(any(Dispense.class));
            verify(prescriptionRepository).save(any(Prescription.class));
            verify(stockLotRepository, never()).save(any());
        }

        @Test
        @DisplayName("should dispense with stock lot and decrement inventory")
        void shouldDispenseWithStockLot() {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(40));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.valueOf(90));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
            verify(stockTransactionRepository).save(any());
        }

        @Test
        @DisplayName("should reject non-dispensable prescription status")
        void shouldRejectNonDispensableStatus() {
            prescription.setStatus(PrescriptionStatus.DRAFT);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
        }

        @Test
        @DisplayName("should reject when stock lot has insufficient quantity")
        void shouldRejectInsufficientLotStock() {
            stockLot.setRemainingQuantity(BigDecimal.valueOf(5));
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Insufficient lot stock");
        }

        @Test
        @DisplayName("should throw when prescription not found")
        void shouldThrowWhenPrescriptionNotFound() {
            DispenseRequestDTO dto = buildRequest();
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should set PARTIALLY_FILLED when quantity dispensed < requested")
        void shouldSetPartiallyFilled() {
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(5));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("PARTIAL").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(5));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
        }

        @Test
        @DisplayName("T-38: should send ready-for-pickup SMS when Rx is fully DISPENSED")
        void shouldSendReadyForPickupSmsOnFullDispense() {
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
            verify(support).notifyReadyForPickup(patient, pharmacy, "Amoxicillin");
        }

        @Test
        @DisplayName("T-38: should NOT send ready-for-pickup SMS on partial fill")
        void shouldNotSendSmsOnPartialFill() {
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(5));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("PARTIAL").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(5));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            verify(support, never()).notifyReadyForPickup(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("getDispense")
    class GetDispense {

        @Test
        @DisplayName("should return dispense when found")
        void shouldReturnDispense() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder().id(dispenseId).build();

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseMapper.toResponseDTO(dispense)).thenReturn(responseDTO);

            DispenseResponseDTO result = service.getDispense(dispenseId);

            assertThat(result.getId()).isEqualTo(dispenseId);
        }

        @Test
        @DisplayName("should throw when dispense not found")
        void shouldThrowWhenNotFound() {
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDispense(dispenseId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("listByPrescription")
    class ListByPrescription {

        @Test
        @DisplayName("should return page of dispenses")
        void shouldReturnPage() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(dispenseRepository.findByPrescriptionId(prescriptionId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            Page<DispenseResponseDTO> result = service.listByPrescription(prescriptionId, pageable);

            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("cancelDispense")
    class CancelDispense {

        @Test
        @DisplayName("should cancel and reverse stock")
        void shouldCancelAndReverseStock() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            dispense.setStockLot(stockLot);
            stockLot.setRemainingQuantity(BigDecimal.valueOf(40));
            inventoryItem.setQuantityOnHand(BigDecimal.valueOf(90));
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("CANCELLED").build();

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
            when(dispenseMapper.toResponseDTO(dispense)).thenReturn(responseDTO);

            DispenseResponseDTO result = service.cancelDispense(dispenseId);

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(50));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.valueOf(100));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
            verify(stockTransactionRepository).save(any());
        }

        @Test
        @DisplayName("should reject cancellation of already cancelled dispense")
        void shouldRejectAlreadyCancelled() {
            Dispense dispense = buildDispense(DispenseStatus.CANCELLED);

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

            assertThatThrownBy(() -> service.cancelDispense(dispenseId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already cancelled");
        }

        @Test
        @DisplayName("should reject cancellation of PENDING dispense")
        void shouldRejectPendingCancellation() {
            Dispense dispense = buildDispense(DispenseStatus.PENDING);

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

            assertThatThrownBy(() -> service.cancelDispense(dispenseId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only completed or partial");
        }
    }

    @Nested
    @DisplayName("createDispense validation")
    class CreateDispenseValidation {

        @Test
        @DisplayName("should reject zero quantity requested")
        void shouldRejectZeroQuantityRequested() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Quantity requested");
        }

        @Test
        @DisplayName("should reject zero quantity dispensed")
        void shouldRejectZeroQuantityDispensed() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Quantity dispensed must");
        }

        @Test
        @DisplayName("should reject dispensed greater than requested")
        void shouldRejectDispensedGreaterThanRequested() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(BigDecimal.valueOf(5));
            dto.setQuantityDispensed(BigDecimal.TEN);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot exceed");
        }

        @Test
        @DisplayName("should reject when prescription hospital != active hospital")
        void shouldRejectCrossHospital() {
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            prescription.setHospital(other);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            DispenseRequestDTO dto = buildRequest();
            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should reject when dto.patientId != prescription.patient")
        void shouldRejectPatientMismatch() {
            DispenseRequestDTO dto = buildRequest();
            dto.setPatientId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Patient does not match");
        }

        @Test
        @DisplayName("should reject when verifiedBy != authenticated user")
        void shouldRejectVerifierMismatch() {
            DispenseRequestDTO dto = buildRequest();
            dto.setVerifiedBy(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("verifiedBy");
        }

        @Test
        @DisplayName("should reject when stockLot belongs to another pharmacy")
        void shouldRejectStockLotFromOtherPharmacy() {
            Pharmacy otherPharmacy = Pharmacy.builder().hospital(hospital).build();
            otherPharmacy.setId(UUID.randomUUID());
            inventoryItem.setPharmacy(otherPharmacy);
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("does not belong");
        }
    }

    @Nested
    @DisplayName("listByPatient / listByPharmacy / getWorkQueue")
    class ListAndQueue {

        @Test
        @DisplayName("listByPatient should map dispenses in scope")
        void listByPatientShouldMap() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findByPatientId(patientId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            Page<DispenseResponseDTO> result = service.listByPatient(patientId, pageable);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("listByPharmacy should enforce scope and map")
        void listByPharmacyShouldMap() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findByPharmacyId(pharmacyId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            Page<DispenseResponseDTO> result = service.listByPharmacy(pharmacyId, pageable);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("getWorkQueue should return mapped DTOs")
        void getWorkQueueShouldMap() {
            Pageable pageable = PageRequest.of(0, 20);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(
                    eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(prescription)));

            Page<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> result =
                    service.getWorkQueue(pageable);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(prescriptionId);
            assertThat(result.getContent().get(0).getPatient().getId()).isEqualTo(patientId);
        }
    }
}
