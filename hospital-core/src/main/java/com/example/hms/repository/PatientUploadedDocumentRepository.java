package com.example.hms.repository;

import com.example.hms.enums.PatientDocumentType;
import com.example.hms.model.PatientUploadedDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientUploadedDocumentRepository extends JpaRepository<PatientUploadedDocument, UUID> {

    /** All non-deleted documents for a patient, ordered by newest first. */
    Page<PatientUploadedDocument> findByPatient_IdAndDeletedAtIsNull(UUID patientId, Pageable pageable);

    /** All non-deleted documents for a patient filtered by document type. */
    Page<PatientUploadedDocument> findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(
            UUID patientId, PatientDocumentType documentType, Pageable pageable);

    /** Fetch a single document only if it belongs to the patient and is not deleted. */
    Optional<PatientUploadedDocument> findByIdAndPatient_IdAndDeletedAtIsNull(UUID id, UUID patientId);
}
