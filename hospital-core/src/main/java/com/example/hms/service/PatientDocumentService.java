package com.example.hms.service;

import com.example.hms.enums.PatientDocumentType;
import com.example.hms.payload.dto.portal.PatientDocumentRequestDTO;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Manages patient-uploaded documents in the patient portal.
 * Files are written to the server filesystem via {@link FileUploadService};
 * this service stores and manages the metadata.
 */
public interface PatientDocumentService {

    /**
     * Upload a new document for the authenticated patient.
     *
     * @param auth    the authenticated user (patient or proxy acting on behalf of a patient)
     * @param file    the multipart file uploaded by the browser
     * @param request metadata (document type, collection date, notes)
     * @return the persisted document metadata
     * @throws IOException if the file cannot be stored
     */
    PatientDocumentResponseDTO uploadDocument(Authentication auth, MultipartFile file, PatientDocumentRequestDTO request)
            throws IOException;

    /**
     * List all documents for the authenticated patient, optionally filtered by type.
     *
     * @param auth         authenticated patient
     * @param documentType optional filter; null returns all types
     * @param pageable     pagination
     */
    Page<PatientDocumentResponseDTO> listDocuments(Authentication auth, PatientDocumentType documentType, Pageable pageable);

    /**
     * Retrieve a single document by ID, verifying it belongs to the authenticated patient.
     */
    PatientDocumentResponseDTO getDocument(Authentication auth, UUID documentId);

    /**
     * Soft-delete a document, verifying it belongs to the authenticated patient.
     */
    void deleteDocument(Authentication auth, UUID documentId);

    /**
     * Resolve a document's bytes for authenticated streaming, verifying it
     * belongs to the authenticated patient. This is the ONLY way document
     * bytes leave the server — the upload tree has no public static
     * mapping (the permitAll /uploads/** hole).
     */
    DocumentPayload downloadDocument(Authentication auth, UUID documentId);

    // ── Staff surface ───────────────────────────────────────────────────
    // A patient uploads a referral letter or an outside lab report so the
    // people treating them can read it. Until this surface existed the only
    // reader was the patient (ROLE_PATIENT, ownership-checked above), so
    // nothing a patient uploaded was reachable from the chart, and the FHIR
    // DocumentReference for it was metadata-only. Staff access is gated on
    // the caller's active hospital and the patient's registration there —
    // the same gate Patient/{id}/$everything and DocumentReference use.

    Page<PatientDocumentResponseDTO> listForPatient(UUID hospitalId, UUID patientId,
                                                    PatientDocumentType documentType, Pageable pageable);

    PatientDocumentResponseDTO getForPatient(UUID hospitalId, UUID patientId, UUID documentId);

    /** Streams the bytes to a staff caller and writes a DATA_ACCESS audit row naming the document. */
    DocumentPayload downloadForPatient(UUID hospitalId, UUID patientId, UUID documentId);

    /** One downloadable document: on-disk path + the headers to serve it with. */
    record DocumentPayload(java.nio.file.Path path, String contentType, String displayName) { }
}
