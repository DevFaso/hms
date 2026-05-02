package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.prescription.PrescriptionTransmission;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.prescription.PrescriptionTransmissionRepository;
import com.example.hms.service.PrescriptionSmsDispatchService;
import com.example.hms.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrescriptionSmsDispatchServiceImpl implements PrescriptionSmsDispatchService {

    private static final String CHANNEL_SMS = "SMS";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_BODY_CHARS = 480;

    private final PrescriptionRepository prescriptionRepository;
    private final PharmacyRepository pharmacyRepository;
    private final PrescriptionTransmissionRepository transmissionRepository;
    private final SmsService smsService;
    private final ControllerAuthUtils authUtils;

    @Override
    @Transactional
    public PrescriptionSmsDispatchResponseDTO dispatch(Authentication auth,
                                                        UUID prescriptionId,
                                                        PrescriptionSmsDispatchRequestDTO request) {
        authUtils.requireAuth(auth);

        Prescription rx = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));

        Pharmacy pharmacy = pharmacyRepository.findById(request.getPharmacyId())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found"));

        validateScope(rx, pharmacy);
        String phone = pharmacy.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("Selected pharmacy has no phone number on file.");
        }

        String body = buildSmsBody(rx, request.getNote());

        PrescriptionTransmission transmission = PrescriptionTransmission.builder()
                .prescription(rx)
                .channel(CHANNEL_SMS)
                .destination(phone)
                .destinationReference(pharmacy.getId().toString())
                .status(STATUS_SENT)
                .attemptCount(1)
                .lastAttemptedAt(LocalDateTime.now())
                .build();

        try {
            smsService.send(phone, body);
        } catch (Exception ex) {
            transmission.setStatus(STATUS_FAILED);
            transmission.setStatusReason(ex.getMessage());
            transmissionRepository.save(transmission);
            log.warn("Prescription SMS dispatch failed for {} → {}: {}",
                    prescriptionId, phone, ex.getMessage());
            throw new BusinessException("SMS provider rejected the message: " + ex.getMessage());
        }

        transmission = transmissionRepository.save(transmission);
        rx.setDispatchChannel(CHANNEL_SMS);
        rx.setDispatchStatus(STATUS_SENT);
        rx.setDispatchedAt(transmission.getLastAttemptedAt());
        rx.setDispatchReference(pharmacy.getId().toString());
        rx.setPharmacyId(pharmacy.getId());
        rx.setPharmacyName(pharmacy.getName());
        rx.setPharmacyContact(phone);
        prescriptionRepository.save(rx);

        log.info("Dispatched prescription {} via SMS to pharmacy {} ({})",
                prescriptionId, pharmacy.getId(), phone);

        return PrescriptionSmsDispatchResponseDTO.builder()
                .prescriptionId(rx.getId())
                .transmissionId(transmission.getId())
                .pharmacyId(pharmacy.getId())
                .pharmacyName(pharmacy.getName())
                .destinationPhone(phone)
                .status(transmission.getStatus())
                .dispatchedAt(transmission.getLastAttemptedAt())
                .build();
    }

    private void validateScope(Prescription rx, Pharmacy pharmacy) {
        if (pharmacy.getPharmacyType() == PharmacyType.HOSPITAL_DISPENSARY) {
            throw new BusinessException(
                "Hospital-dispensary pharmacies are dispensed in-house and cannot be dispatched by SMS.");
        }
        UUID rxHospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        UUID pharmHospitalId = pharmacy.getHospital() != null ? pharmacy.getHospital().getId() : null;
        if (rxHospitalId == null || pharmHospitalId == null
                || !rxHospitalId.equals(pharmHospitalId)) {
            throw new AccessDeniedException(
                "Pharmacy and prescription must belong to the same hospital.");
        }
    }

    private String buildSmsBody(Prescription rx, String note) {
        StringBuilder sb = new StringBuilder("HMS Rx: ");
        sb.append(safe(rx.getMedicationName()));
        if (rx.getDosage() != null) {
            sb.append(' ').append(rx.getDosage());
            if (rx.getDoseUnit() != null) sb.append(rx.getDoseUnit());
        }
        if (rx.getRoute() != null) sb.append(' ').append(rx.getRoute());
        if (rx.getFrequency() != null) sb.append(' ').append(rx.getFrequency());
        if (rx.getDuration() != null) sb.append(" x ").append(rx.getDuration());
        if (rx.getInstructions() != null && !rx.getInstructions().isBlank()) {
            sb.append(". ").append(rx.getInstructions());
        }
        if (note != null && !note.isBlank()) {
            sb.append(". Note: ").append(note.trim());
        }
        if (rx.getPatient() != null) {
            String pname = ((rx.getPatient().getFirstName() != null ? rx.getPatient().getFirstName() : "")
                    + " " + (rx.getPatient().getLastName() != null ? rx.getPatient().getLastName() : "")).trim();
            if (!pname.isEmpty()) {
                sb.append(" (Patient: ").append(pname).append(')');
            }
        }
        String body = sb.toString();
        return body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
