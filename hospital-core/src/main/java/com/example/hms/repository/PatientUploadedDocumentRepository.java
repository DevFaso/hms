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

    /**
     * FHIR read path: same rows as {@link #findByPatient_IdAndDeletedAtIsNull}
     * but with the mapper-walked associations fetched in the page query —
     * without the graph, mapping a full page costs one uploader query per row.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "uploadedByUser"})
    Page<PatientUploadedDocument> findByPatient_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID patientId, Pageable pageable);

    /**
     * QA D961: the staff list at a hospital: the patient's own uploads, and
     * the documents this hospital issued (another hospital's certificate is
     * not shown).
     */
    @org.springframework.data.jpa.repository.Query(
        "select d from PatientUploadedDocument d where d.patient.id = :patientId and d.deletedAt is null"
            + " and (d.issuingHospital is null or d.issuingHospital.id = :hospitalId)")
    Page<PatientUploadedDocument> findForStaff(
        @org.springframework.data.repository.query.Param("patientId") UUID patientId,
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        Pageable pageable);

    /** {@link #findForStaff} filtered by document type. */
    @org.springframework.data.jpa.repository.Query(
        "select d from PatientUploadedDocument d where d.patient.id = :patientId and d.deletedAt is null"
            + " and d.documentType = :documentType"
            + " and (d.issuingHospital is null or d.issuingHospital.id = :hospitalId)")
    Page<PatientUploadedDocument> findForStaffByType(
        @org.springframework.data.repository.query.Param("patientId") UUID patientId,
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        @org.springframework.data.repository.query.Param("documentType") PatientDocumentType documentType,
        Pageable pageable);

    /**
     * QA D961 (review of #971): the FHIR read paths (DocumentReference search,
     * Patient/$everything) at a hospital: the {@link #findForStaff} rows, newest
     * first, with the mapper-walked associations fetched in the page query.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "uploadedByUser"})
    @org.springframework.data.jpa.repository.Query(
        "select d from PatientUploadedDocument d where d.patient.id = :patientId and d.deletedAt is null"
            + " and (d.issuingHospital is null or d.issuingHospital.id = :hospitalId) order by d.createdAt desc")
    Page<PatientUploadedDocument> findForStaffNewestFirst(
        @org.springframework.data.repository.query.Param("patientId") UUID patientId,
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        Pageable pageable);

    /** Fetch a single document only if it belongs to the patient and is not deleted. */
    Optional<PatientUploadedDocument> findByIdAndPatient_IdAndDeletedAtIsNull(UUID id, UUID patientId);
}
