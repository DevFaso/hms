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
import com.example.hms.repository.pharmacy.InventoryItemRepository;
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
    private final InventoryItemRepository inventoryItemRepository;

    private Dispense loadDispenseForPayment(PharmacyPaymentRequestDTO dto, UUID hospitalId) {
        Dispense dispense = dispenseRepository.findLockedById(dto.getDispenseId())
                .orElseThrow(() -> new ResourceNotFoundException("dispense.notfound"));
        if (dispense.getPatient() == null
                || !dispense.getPatient().getId().equals(dto.getPatientId())) {
            throw new BusinessException("pharmacy.payment.patientMismatch");
        }
        if (dispense.getPharmacy() == null
                || dispense.getPharmacy().getHospital() == null
                || !hospitalId.equals(dispense.getPharmacy().getHospital().getId())) {
            throw new ResourceNotFoundException("dispense.notfound");
        }
        // QA D404: a cancelled dispense has nothing left to pay for.
        if (dispense.getStatus() == com.example.hms.enums.DispenseStatus.CANCELLED) {
            throw new BusinessException("pharmacy.payment.dispenseCancelled");
        }
        return dispense;
    }

    /**
     * QA D204: a dispense is paid once. The same payment sent again (a retry,
     * a double click) answers with the one already recorded; a different
     * one is refused instead of being added on top.
     */
    private java.util.Optional<PharmacyPayment> findReplayedPayment(Dispense dispense, PharmacyPaymentRequestDTO dto) {
        java.util.Optional<PharmacyPayment> existing =
                paymentRepository.findFirstByDispense_IdOrderByCreatedAtAsc(dispense.getId());
        if (existing.isEmpty()) {
            return existing;
        }
        PharmacyPayment prior = existing.get();
        if (prior.getAmount() != null && prior.getAmount().compareTo(dto.getAmount()) == 0
                && prior.getPaymentMethod() == dto.getPaymentMethod()) {
            return existing;
        }
        throw new BusinessException("pharmacy.payment.alreadyPaid");
    }

    @Override
    @Transactional
    public PharmacyPaymentResponseDTO createPayment(PharmacyPaymentRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();

        validateCreateRequest(dto, hospitalId);

        Dispense dispense = loadDispenseForPayment(dto, hospitalId);

        java.util.Optional<PharmacyPayment> replay = findReplayedPayment(dispense, dto);
        if (replay.isPresent()) {
            return paymentMapper.toResponseDTO(replay.get());
        }
        requireAmountMatchesCharge(dispense, dto.getAmount());

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));

        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", hospitalId));

        // The payer of record is the authenticated cashier/pharmacist.
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("pharmacy.currentUserUnknown");
        }
        if (dto.getReceivedBy() != null && !currentUserId.equals(dto.getReceivedBy())) {
            throw new BusinessException("pharmacy.payment.receivedByMismatch");
        }
        User receivedBy = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));

        String providerReference = dto.getReferenceNumber();
        String currency = dto.getCurrency() != null ? dto.getCurrency() : "XOF";

        if (dto.getPaymentMethod() == PharmacyPaymentMethod.MOBILE_MONEY) {
            String phone = patient.getPhoneNumberPrimary();
            if (phone == null || phone.isBlank()) {
                throw new BusinessException("pharmacy.payment.noMobileMoneyPhone");
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
                throw new BusinessException("pharmacy.payment.mobileMoneyFailed");
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

    /**
     * QA D264 (D204 did not hold): the D204 fix stopped a second payment and
     * fractional or non-positive amounts, but nothing in the system said what
     * a dispense costs, so any first amount was taken. The charge now comes
     * from the inventory item's unit price; a dispense is paid once, for
     * exactly that charge, and an unpriced one cannot be paid at all.
     */
    private void requireAmountMatchesCharge(Dispense dispense, BigDecimal amount) {
        BigDecimal charge = DispenseCharge.of(dispense, inventoryItemRepository)
                .orElseThrow(() -> new BusinessException("pharmacy.payment.noPrice"));
        if (charge.compareTo(amount) != 0) {
            throw new BusinessException("pharmacy.payment.amountMismatch", charge.toPlainString());
        }
    }

    private void validateCreateRequest(PharmacyPaymentRequestDTO dto, UUID hospitalId) {
        if (dto == null) {
            throw new BusinessException("pharmacy.payment.requestRequired");
        }
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("pharmacy.payment.amountPositive");
        }
        // QA D204 (D184): the franc CFA has no minor unit.
        if (dto.getAmount().stripTrailingZeros().scale() > 0) {
            throw new BusinessException("billing.payment.wholeAmountOnly");
        }
        if (dto.getPaymentMethod() == null) {
            throw new BusinessException("pharmacy.payment.methodRequired");
        }
        // SUPER_ADMIN may have no active hospital; require one for payment creation.
        if (hospitalId == null) {
            throw new BusinessException("pharmacy.payment.hospitalRequired");
        }
        if (!hospitalId.equals(dto.getHospitalId())) {
            throw new BusinessException("pharmacy.payment.hospitalMismatch");
        }
    }
}
