package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
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
        String phone = requirePharmacyPhone(pharmacy);
        String body = buildSmsBody(rx, request.getNote());

        PrescriptionTransmission transmission = sendAndPersist(rx, pharmacy, phone, body);

        applyDispatchToPrescription(rx, pharmacy, phone, transmission);
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
        if (!pharmacy.isActive()) {
            throw new BusinessException("Selected pharmacy is inactive and cannot accept new dispatches.");
        }
        UUID rxHospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        UUID pharmHospitalId = pharmacy.getHospital() != null ? pharmacy.getHospital().getId() : null;
        if (rxHospitalId == null || pharmHospitalId == null
                || !rxHospitalId.equals(pharmHospitalId)) {
            throw new AccessDeniedException(
                "Pharmacy and prescription must belong to the same hospital.");
        }
    }

    private String requirePharmacyPhone(Pharmacy pharmacy) {
        String phone = pharmacy.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("Selected pharmacy has no phone number on file.");
        }
        return phone;
    }

    private PrescriptionTransmission sendAndPersist(Prescription rx, Pharmacy pharmacy,
                                                    String phone, String body) {
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
                    rx.getId(), phone, ex.getMessage(), ex);
            throw new BusinessException("SMS provider rejected the message: " + ex.getMessage());
        }
        return transmissionRepository.save(transmission);
    }

    private void applyDispatchToPrescription(Prescription rx, Pharmacy pharmacy, String phone,
                                              PrescriptionTransmission transmission) {
        rx.setDispatchChannel(CHANNEL_SMS);
        rx.setDispatchStatus(STATUS_SENT);
        rx.setDispatchedAt(transmission.getLastAttemptedAt());
        rx.setDispatchReference(pharmacy.getId().toString());
        rx.setPharmacyId(pharmacy.getId());
        rx.setPharmacyName(pharmacy.getName());
        rx.setPharmacyContact(phone);
        prescriptionRepository.save(rx);
    }

    private String buildSmsBody(Prescription rx, String note) {
        StringBuilder sb = new StringBuilder("HMS Rx: ").append(safe(rx.getMedicationName()));
        appendDose(sb, rx);
        appendIfPresent(sb, rx.getRoute(), " ");
        appendIfPresent(sb, rx.getFrequency(), " ");
        appendIfPresent(sb, rx.getDuration(), " x ");
        appendIfNotBlank(sb, rx.getInstructions(), ". ");
        appendIfNotBlank(sb, note, ". Note: ");
        appendPatient(sb, rx.getPatient());
        String body = sb.toString();
        return body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
    }

    private static void appendDose(StringBuilder sb, Prescription rx) {
        if (rx.getDosage() == null) {
            return;
        }
        sb.append(' ').append(rx.getDosage());
        if (rx.getDoseUnit() != null) {
            sb.append(rx.getDoseUnit());
        }
    }

    private static void appendIfPresent(StringBuilder sb, String value, String prefix) {
        if (value != null) {
            sb.append(prefix).append(value);
        }
    }

    private static void appendIfNotBlank(StringBuilder sb, String value, String prefix) {
        if (value != null && !value.isBlank()) {
            sb.append(prefix).append(value.trim());
        }
    }

    private static void appendPatient(StringBuilder sb, Patient patient) {
        if (patient == null) {
            return;
        }
        String first = patient.getFirstName() != null ? patient.getFirstName() : "";
        String last = patient.getLastName() != null ? patient.getLastName() : "";
        String name = (first + " " + last).trim();
        if (!name.isEmpty()) {
            sb.append(" (Patient: ").append(name).append(')');
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
