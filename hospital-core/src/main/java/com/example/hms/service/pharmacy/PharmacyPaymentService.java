package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.PharmacyPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * T-41: pharmacy payment service — records cash and mobile-money payments against
 * a dispense, and surfaces payment history to the patient portal (T-45).
 */
public interface PharmacyPaymentService {

    /**
     * Record a new pharmacy payment. For {@code MOBILE_MONEY}, the request is
     * routed through {@link com.example.hms.service.pharmacy.payment.MobileMoneyGateway}
     * and the returned provider reference is stored on the payment record.
     */
    PharmacyPaymentResponseDTO createPayment(PharmacyPaymentRequestDTO dto);

    PharmacyPaymentResponseDTO getPayment(UUID id);

    Page<PharmacyPaymentResponseDTO> listByDispense(UUID dispenseId, Pageable pageable);

    Page<PharmacyPaymentResponseDTO> listByPatient(UUID patientId, Pageable pageable);

    /**
     * Patient self-service variant: lists pharmacy payments for the given patient
     * ID without going through staff hospital-scope validation. Callers MUST have
     * already verified that the {@code patientId} belongs to the authenticated
     * patient (e.g. via {@code PatientPortalService.resolvePatientId(auth)}).
     */
    Page<PharmacyPaymentResponseDTO> listByPatientForSelf(UUID patientId, Pageable pageable);
}
