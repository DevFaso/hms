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
}
