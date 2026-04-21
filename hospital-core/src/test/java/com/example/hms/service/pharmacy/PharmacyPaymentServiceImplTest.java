package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacyPaymentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.service.pharmacy.payment.MobileMoneyGateway;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-41: pharmacy payment service unit tests. Covers validation, tenant isolation,
 * cash vs mobile-money dispatch, and gateway failure handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyPaymentServiceImpl")
class PharmacyPaymentServiceImplTest {

    @Mock private PharmacyPaymentRepository paymentRepository;
    @Mock private DispenseRepository dispenseRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private PharmacyPaymentMapper paymentMapper;
    @Mock private MobileMoneyGateway mobileMoneyGateway;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;

    @InjectMocks private PharmacyPaymentServiceImpl service;

    private UUID hospitalId;
    private UUID dispenseId;
    private UUID patientId;
    private UUID userId;
    private Hospital hospital;
    private Patient patient;
    private Pharmacy pharmacy;
    private Dispense dispense;
    private User user;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        dispenseId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        userId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setPhoneNumberPrimary("+22670000000");

        pharmacy = new Pharmacy();
        pharmacy.setId(UUID.randomUUID());
        pharmacy.setHospital(hospital);

        dispense = new Dispense();
        dispense.setId(dispenseId);
        dispense.setPatient(patient);
        dispense.setPharmacy(pharmacy);
        dispense.setMedicationName("Amoxicilline 500mg");

        user = new User();
        user.setId(userId);
    }

    private PharmacyPaymentRequestDTO request(PharmacyPaymentMethod method) {
        return PharmacyPaymentRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .paymentMethod(method)
                .amount(new BigDecimal("2500"))
                .currency("XOF")
                .receivedBy(userId)
                .build();
    }

    @Test
    @DisplayName("cash payment: persists record and does not call mobile-money gateway")
    void cashPaymentSucceeds() {
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(paymentMapper.toEntity(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyPayment());
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PharmacyPayment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(paymentMapper.toResponseDTO(any()))
                .thenReturn(PharmacyPaymentResponseDTO.builder().build());

        PharmacyPaymentResponseDTO result = service.createPayment(dto);

        assertThat(result).isNotNull();
        verify(mobileMoneyGateway, never()).charge(any());
        verify(paymentRepository).save(any());
    }

    @Test
    @DisplayName("mobile-money payment: charges gateway and stores provider reference")
    void mobileMoneyPaymentUsesGateway() {
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.MOBILE_MONEY);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mobileMoneyGateway.charge(any())).thenReturn(
                new MobileMoneyGateway.MobileMoneyCharge(
                        "MOCK-abc", "COMPLETED", new BigDecimal("2500"), "XOF"));
        when(paymentMapper.toEntity(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyPayment());
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PharmacyPayment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(paymentMapper.toResponseDTO(any()))
                .thenReturn(PharmacyPaymentResponseDTO.builder().build());

        service.createPayment(dto);

        verify(mobileMoneyGateway).charge(any());
        verify(paymentRepository).save(org.mockito.ArgumentMatchers.argThat(
                p -> "MOCK-abc".equals(p.getReferenceNumber())));
    }

    @Test
    @DisplayName("mobile-money payment: gateway failure surfaces as BusinessException")
    void mobileMoneyGatewayFailureThrowsBusinessException() {
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.MOBILE_MONEY);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mobileMoneyGateway.charge(any()))
                .thenThrow(new MobileMoneyGateway.MobileMoneyException("declined"));

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("declined");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("mobile-money payment: rejects patient with no phone on file")
    void mobileMoneyRejectsPatientWithoutPhone() {
        patient.setPhoneNumberPrimary(null);
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.MOBILE_MONEY);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("phone");
        verify(mobileMoneyGateway, never()).charge(any());
    }

    @Test
    @DisplayName("rejects non-positive amount")
    void rejectsZeroAmount() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyPaymentRequestDTO dto = PharmacyPaymentRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .paymentMethod(PharmacyPaymentMethod.CASH)
                .amount(BigDecimal.ZERO)
                .receivedBy(userId)
                .build();

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("rejects cross-hospital dispense (tenant isolation)")
    void rejectsCrossHospitalDispense() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        pharmacy.setHospital(other);

        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejects dispense whose patient does not match the request")
    void rejectsPatientMismatch() {
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        dispense.setPatient(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Patient");
    }

    @Test
    @DisplayName("rejects null DTO")
    void rejectsNullDto() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createPayment(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("rejects null payment method")
    void rejectsNullPaymentMethod() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyPaymentRequestDTO dto = PharmacyPaymentRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .paymentMethod(null)
                .amount(new BigDecimal("100"))
                .build();

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("method");
    }

    @Test
    @DisplayName("rejects null amount")
    void rejectsNullAmount() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyPaymentRequestDTO dto = PharmacyPaymentRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .paymentMethod(PharmacyPaymentMethod.CASH)
                .amount(null)
                .build();

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("rejects when DTO hospitalId does not match active hospital")
    void rejectsHospitalIdMismatch() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);
        dto.setHospitalId(UUID.randomUUID());

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hospital");
    }

    @Test
    @DisplayName("rejects when dispense is not found")
    void rejectsDispenseNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejects when dispense.patient is null")
    void rejectsDispenseWithNullPatient() {
        dispense.setPatient(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Patient");
    }

    @Test
    @DisplayName("rejects when dispense.pharmacy is null (tenant check)")
    void rejectsDispenseWithNullPharmacy() {
        dispense.setPharmacy(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejects when pharmacy.hospital is null (tenant check)")
    void rejectsDispenseWithNullHospitalOnPharmacy() {
        pharmacy.setHospital(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejects when patient is not found in repository")
    void rejectsPatientNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejects when hospital is not found in repository")
    void rejectsHospitalNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejects when current user id is null")
    void rejectsNullCurrentUserId() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(roleValidator.getCurrentUserId()).thenReturn(null);

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    @DisplayName("rejects when receivedBy does not match the authenticated user")
    void rejectsReceivedByMismatch() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);

        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);
        dto.setReceivedBy(UUID.randomUUID());

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("receivedBy");
    }

    @Test
    @DisplayName("rejects when current user record is missing")
    void rejectsCurrentUserNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPayment(request(PharmacyPaymentMethod.CASH)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("mobile-money: rejects patient with blank phone number")
    void mobileMoneyRejectsBlankPhone() {
        patient.setPhoneNumberPrimary("   ");
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.MOBILE_MONEY);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.createPayment(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("phone");
    }

    @Test
    @DisplayName("defaults currency to XOF when DTO currency is null")
    void defaultsCurrencyToXof() {
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);
        dto.setCurrency(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(paymentMapper.toEntity(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyPayment());
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PharmacyPayment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(paymentMapper.toResponseDTO(any()))
                .thenReturn(PharmacyPaymentResponseDTO.builder().build());

        service.createPayment(dto);

        verify(paymentRepository).save(org.mockito.ArgumentMatchers.argThat(
                p -> "XOF".equals(p.getCurrency())));
    }

    @Test
    @DisplayName("accepts null receivedBy (no identity-mismatch check)")
    void acceptsNullReceivedBy() {
        PharmacyPaymentRequestDTO dto = request(PharmacyPaymentMethod.CASH);
        dto.setReceivedBy(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(paymentMapper.toEntity(any(), any(), any(), any(), any()))
                .thenReturn(new PharmacyPayment());
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PharmacyPayment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(paymentMapper.toResponseDTO(any()))
                .thenReturn(PharmacyPaymentResponseDTO.builder().build());

        assertThat(service.createPayment(dto)).isNotNull();
    }

    @Test
    @DisplayName("getPayment: returns payment when in active hospital scope")
    void getPaymentReturnsInScope() {
        UUID paymentId = UUID.randomUUID();
        PharmacyPayment entity = new PharmacyPayment();
        entity.setId(paymentId);
        entity.setHospital(hospital);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(entity));
        when(paymentMapper.toResponseDTO(entity))
                .thenReturn(PharmacyPaymentResponseDTO.builder().build());

        assertThat(service.getPayment(paymentId)).isNotNull();
    }

    @Test
    @DisplayName("getPayment: throws when payment not found")
    void getPaymentNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPayment(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getPayment: rejects cross-hospital read")
    void getPaymentCrossHospitalRejected() {
        UUID paymentId = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        PharmacyPayment entity = new PharmacyPayment();
        entity.setId(paymentId);
        entity.setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getPayment(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getPayment: rejects payment with null hospital")
    void getPaymentNullHospitalRejected() {
        UUID paymentId = UUID.randomUUID();
        PharmacyPayment entity = new PharmacyPayment();
        entity.setId(paymentId);
        entity.setHospital(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getPayment(paymentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("listByDispense: delegates to repository with pageable")
    void listByDispenseDelegates() {
        UUID id = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(paymentRepository.findByDispenseIdAndHospital_Id(eq(id), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByDispense(id, org.springframework.data.domain.PageRequest.of(0, 10)))
                .isNotNull();
    }

    @Test
    @DisplayName("listByPatient: delegates to repository with pageable")
    void listByPatientDelegates() {
        UUID id = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(paymentRepository.findByPatientIdAndHospital_Id(eq(id), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByPatient(id, org.springframework.data.domain.PageRequest.of(0, 10)))
                .isNotNull();
    }
}
