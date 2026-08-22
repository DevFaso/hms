package com.example.hms.service;

import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface PrescriptionService {

    PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request, Locale locale);

    PrescriptionResponseDTO getPrescriptionById(UUID id, Locale locale);

    /**
     * Sign a prescription (P2 #16).
     *
     * <p>SIGNED used to be a client-supplied status with nothing behind it. This
     * is the only path that can set it: it verifies the signer is the
     * prescription's own prescriber, applies the controlled-substance gates, and
     * records who signed, when, and a SHA-256 digest of what they signed.
     */
    PrescriptionResponseDTO signPrescription(UUID id, Locale locale);

    /**
     * Co-sign a prescription that declared it needs one (P2 #15).
     *
     * <p>The {@code cosignedBy}/{@code cosignedAt} columns existed since the
     * pharmacy module with no path ever writing them, so a prescription flagged
     * {@code requiresCosign} was permanently unsignable once the gates went in.
     * The co-signer must be a prescriber OTHER than the prescription's own —
     * a second pair of eyes is the entire point.
     */
    PrescriptionResponseDTO cosignPrescription(UUID id, Locale locale);

    Page<PrescriptionResponseDTO> list(UUID patientId, UUID staffId, UUID encounterId, Pageable pageable, Locale locale);

    PrescriptionResponseDTO updatePrescription(UUID id, PrescriptionRequestDTO request, Locale locale);

    void deletePrescription(UUID id, Locale locale);

    // legacy convenience (optional)
    List<PrescriptionResponseDTO> getPrescriptionsByPatientId(UUID patientId, Locale locale);
    List<PrescriptionResponseDTO> getPrescriptionsByStaffId(UUID staffId, Locale locale);
    List<PrescriptionResponseDTO> getPrescriptionsByEncounterId(UUID encounterId, Locale locale);
}
