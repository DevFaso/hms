package com.example.hms.service;

import com.example.hms.payload.dto.clinical.MedicalCertificateRequestDTO;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;

import java.util.UUID;

/** QA D961: medical certificates and sick-leave notes issued by a doctor from a visit. */
public interface MedicalCertificateService {

    /**
     * Generate the signed certificate PDF for the visit, file it in the
     * patient's documents (staff chart and patient portal) and return it.
     */
    PatientDocumentResponseDTO issue(UUID encounterId, MedicalCertificateRequestDTO request);
}
