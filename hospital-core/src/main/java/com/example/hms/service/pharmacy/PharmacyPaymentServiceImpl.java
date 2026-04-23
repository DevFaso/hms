package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacyPaymentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * T-41: pharmacy payment service implementation. Writes {@link PharmacyPayment}
 * records scoped to the caller's active hospital, and routes {@code MOBILE_MONEY}
 * payments through the configured {@link MobileMoneyGateway} (T-42).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PharmacyPaymentServiceImpl implements PharmacyPaymentService {

    private static final String AUDIT_ENTITY = "PHARMACY_PAYMENT";

    private final PharmacyPaymentRepository paymentRepository;
    private final DispenseRepository dispenseRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final PharmacyPaymentMapper paymentMapper;
    private final MobileMoneyGateway mobileMoneyGateway;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;

    @Override
    @Transactional
    public PharmacyPaymentResponseDTO createPayment(PharmacyPaymentRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();

        validateCreateRequest(dto, hospitalId);

        Dispense dispense = dispenseRepository.findById(dto.getDispenseId())
                .orElseThrow(() -> new ResourceNotFoundException("dispense.notfound"));
        if (dispense.getPatient() == null
                || !dispense.getPatient().getId().equals(dto.getPatientId())) {
            throw new BusinessException("Patient does not match the dispense record");
        }
        if (dispense.getPharmacy() == null
                || dispense.getPharmacy().getHospital() == null
                || !hospitalId.equals(dispense.getPharmacy().getHospital().getId())) {
            throw new ResourceNotFoundException("dispense.notfound");
        }

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));

        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        // The payer of record is the authenticated cashier/pharmacist.
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        if (dto.getReceivedBy() != null && !currentUserId.equals(dto.getReceivedBy())) {
            throw new BusinessException("receivedBy must match the authenticated user");
        }
        User receivedBy = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));

        String providerReference = dto.getReferenceNumber();
        String currency = dto.getCurrency() != null ? dto.getCurrency() : "XOF";

        if (dto.getPaymentMethod() == PharmacyPaymentMethod.MOBILE_MONEY) {
            String phone = patient.getPhoneNumberPrimary();
            if (phone == null || phone.isBlank()) {
                throw new BusinessException("Patient has no mobile-money phone number on file");
            }
            try {
                MobileMoneyGateway.MobileMoneyCharge charge = mobileMoneyGateway.charge(
                        new MobileMoneyGateway.MobileMoneyChargeRequest(
                                phone,
                                dto.getAmount(),
                                currency,
                                "Pharmacie: " + dispense.getMedicationName(),
                                dispense.getId().toString()));
                providerReference = charge.providerReference();
            } catch (MobileMoneyGateway.MobileMoneyException e) {
                log.warn("Mobile-money charge failed for dispense {}: {}", dispense.getId(), e.getMessage());
                throw new BusinessException("Mobile-money charge failed: " + e.getMessage());
            }
        }

        PharmacyPayment entity = paymentMapper.toEntity(dto, dispense, patient, hospital, receivedBy);
        entity.setReferenceNumber(providerReference);
        entity.setCurrency(currency);
        PharmacyPayment saved = paymentRepository.save(entity);

        support.logAudit(AuditEventType.PAYMENT_POSTED,
                "Pharmacy payment " + saved.getPaymentMethod() + " " + saved.getAmount() + " "
                        + saved.getCurrency() + " for dispense " + dispense.getId(),
                saved.getId().toString(),
                AUDIT_ENTITY);

        return paymentMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PharmacyPaymentResponseDTO getPayment(UUID id) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PharmacyPayment entity = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.payment.notfound"));
        // SUPER_ADMIN with no active hospital may read across hospitals; otherwise enforce scope.
        if (hospitalId != null
                && (entity.getHospital() == null || !hospitalId.equals(entity.getHospital().getId()))) {
            throw new ResourceNotFoundException("pharmacy.payment.notfound");
        }
        return paymentMapper.toResponseDTO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyPaymentResponseDTO> listByDispense(UUID dispenseId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // SUPER_ADMIN unscoped read (no active hospital).
            return paymentRepository.findByDispenseId(dispenseId, pageable).map(paymentMapper::toResponseDTO);
        }
        return paymentRepository.findByDispenseIdAndHospital_Id(dispenseId, hospitalId, pageable)
                .map(paymentMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyPaymentResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return paymentRepository.findByPatientId(patientId, pageable).map(paymentMapper::toResponseDTO);
        }
        return paymentRepository.findByPatientIdAndHospital_Id(patientId, hospitalId, pageable)
                .map(paymentMapper::toResponseDTO);
    }

    /**
     * Patient self-service read path: does NOT call {@link RoleValidator#requireActiveHospitalId()}
     * because a patient caller has no staff hospital assignment. The caller (patient portal
     * controller) is responsible for verifying that {@code patientId} is the authenticated
     * patient's own ID before invoking this method.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyPaymentResponseDTO> listByPatientForSelf(UUID patientId, Pageable pageable) {
        return paymentRepository.findByPatientId(patientId, pageable).map(paymentMapper::toResponseDTO);
    }

    private void validateCreateRequest(PharmacyPaymentRequestDTO dto, UUID hospitalId) {
        if (dto == null) {
            throw new BusinessException("Payment request is required");
        }
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be positive");
        }
        if (dto.getPaymentMethod() == null) {
            throw new BusinessException("Payment method is required");
        }
        // SUPER_ADMIN may have no active hospital; require one for payment creation.
        if (hospitalId == null) {
            throw new BusinessException("Active hospital context is required to record payments");
        }
        if (!hospitalId.equals(dto.getHospitalId())) {
            throw new BusinessException("Payment hospital does not match the active hospital");
        }
    }
}
