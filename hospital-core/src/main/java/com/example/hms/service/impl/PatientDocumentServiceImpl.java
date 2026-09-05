package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.PatientDocumentMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.PatientDocumentRequestDTO;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.PatientDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientDocumentServiceImpl implements PatientDocumentService {

    private static final String MSG_UNABLE_RESOLVE_USER = "Unable to resolve user from authentication";

    private final ControllerAuthUtils authUtils;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final PatientUploadedDocumentRepository documentRepository;
    private final FileUploadService fileUploadService;
    private final PatientDocumentMapper documentMapper;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogService auditEventLogService;

    @Override
    // rollbackFor is load-bearing, not decoration. Spring rolls back on
    // RuntimeException and Error only, so without this an IOException thrown
    // part-way through — the storage write failing after the row is
    // persisted, which is exactly how an upload fails — would COMMIT the
    // document row and leave a chart pointing at a file that does not exist.
    @Transactional(rollbackFor = IOException.class)
    public PatientDocumentResponseDTO uploadDocument(Authentication auth,
                                                     MultipartFile file,
                                                     PatientDocumentRequestDTO request) throws IOException {
        UUID userId = resolveUserId(auth);
        Patient patient = resolvePatient(userId);
        User uploader = resolveUser(userId);

        FileUploadService.StoredFileDescriptor descriptor = fileUploadService.uploadPatientDocument(file, userId);

        PatientUploadedDocument doc = PatientUploadedDocument.builder()
                .patient(patient)
                .uploadedByUser(uploader)
                .documentType(request.getDocumentType())
                .displayName(descriptor.displayName())
                .filePath(descriptor.storageKey())
                .fileUrl(descriptor.publicUrl())
                .mimeType(descriptor.contentType())
                .fileSizeBytes(descriptor.sizeBytes())
                .checksumSha256(descriptor.sha256())
                .collectionDate(request.getCollectionDate())
                .notes(request.getNotes())
                .build();

        PatientUploadedDocument saved = documentRepository.save(doc);
        log.info("Patient {} uploaded document {} ({})", patient.getId(), saved.getId(), request.getDocumentType());
        return documentMapper.toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PatientDocumentResponseDTO> listDocuments(Authentication auth,
                                                          PatientDocumentType documentType,
                                                          Pageable pageable) {
        UUID patientId = resolvePatientId(auth);
        Page<PatientUploadedDocument> page = documentType != null
                ? documentRepository.findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(patientId, documentType, pageable)
                : documentRepository.findByPatient_IdAndDeletedAtIsNull(patientId, pageable);
        return page.map(documentMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientDocumentResponseDTO getDocument(Authentication auth, UUID documentId) {
        PatientUploadedDocument doc = requireOwnDocument(resolvePatientId(auth), documentId);
        return documentMapper.toDto(doc);
    }

    @Override
    @Transactional
    public void deleteDocument(Authentication auth, UUID documentId) {
        UUID patientId = resolvePatientId(auth);
        PatientUploadedDocument doc = requireOwnDocument(patientId, documentId);
        doc.setDeletedAt(LocalDateTime.now());
        documentRepository.save(doc);
        log.info("Patient {} soft-deleted document {}", patientId, documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentPayload downloadDocument(Authentication auth, UUID documentId) {
        PatientUploadedDocument doc = requireOwnDocument(resolvePatientId(auth), documentId);
        // filePath is the server-assigned storage key from upload time —
        // never client input — and resolveStoredFile pins it inside the
        // patient-documents subdirectory.
        java.nio.file.Path path = fileUploadService.resolveStoredFile(doc.getFilePath(), "patient-documents");
        String contentType = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
        String displayName = doc.getDisplayName() != null ? doc.getDisplayName() : "document";
        return new DocumentPayload(path, contentType, displayName);
    }

    // ── Staff surface ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<PatientDocumentResponseDTO> listForPatient(UUID hospitalId, UUID patientId,
                                                           PatientDocumentType documentType, Pageable pageable) {
        requireRegisteredAt(hospitalId, patientId);
        Page<PatientUploadedDocument> page = documentType == null
                ? documentRepository.findByPatient_IdAndDeletedAtIsNull(patientId, pageable)
                : documentRepository.findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(patientId, documentType, pageable);
        return page.map(documentMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PatientDocumentResponseDTO getForPatient(UUID hospitalId, UUID patientId, UUID documentId) {
        requireRegisteredAt(hospitalId, patientId);
        return documentMapper.toDto(requireOwnDocument(patientId, documentId));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentPayload downloadForPatient(UUID hospitalId, UUID patientId, UUID documentId) {
        PatientHospitalRegistration registration = requireRegisteredAt(hospitalId, patientId);
        PatientUploadedDocument doc = requireOwnDocument(patientId, documentId);
        java.nio.file.Path path = fileUploadService.resolveStoredFile(doc.getFilePath(), "patient-documents");
        auditStaffDownload(hospitalId, registration, doc);
        String contentType = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
        String displayName = doc.getDisplayName() != null ? doc.getDisplayName() : "document";
        return new DocumentPayload(path, contentType, displayName);
    }

    /**
     * Staff see a patient's uploads only through a hospital the patient is
     * registered at. No hospital (a super-admin in global view) is a 400 that
     * says so; a patient not registered at the caller's hospital is a 404 —
     * the same answer as a document that does not exist, so the endpoint
     * does not confirm that a patient is known elsewhere.
     */
    private PatientHospitalRegistration requireRegisteredAt(UUID hospitalId, UUID patientId) {
        if (hospitalId == null) {
            throw new BusinessException(
                    "An active hospital is required: patient documents are read through the hospital "
                            + "the patient is registered at. Select a hospital first.");
        }
        if (patientId == null) {
            throw new ResourceNotFoundException("Patient not found: null");
        }
        return registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientId));
    }

    /**
     * The chart-level PATIENT_ACCESS row is written by the interceptor and
     * de-duplicated per (user, patient) for a window; a document download is
     * a disclosure in its own right and gets its own row every time. The
     * description carries the document id and type only — the display name
     * is patient-chosen and can itself be PHI ("hiv-results.pdf").
     *
     * <p>The row is anchored structurally, not in free text: the hospital
     * snapshot comes from the registration that authorised the read, and the
     * actor's assignment at that hospital (when one exists — a super-admin
     * scoped via X-Hospital-Id has none) links the row to the assignment so
     * the per-hospital audit queries find it. The audit service derives no
     * assignment from {@code userId} alone.
     */
    private void auditStaffDownload(UUID hospitalId, PatientHospitalRegistration registration,
                                    PatientUploadedDocument doc) {
        try {
            UUID actorId = authUtils.resolveUserId(SecurityContextHolder.getContext().getAuthentication())
                    .orElse(null);
            UserRoleHospitalAssignment assignment = actorId == null ? null
                    : assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId)
                            .orElse(null);
            String hospitalName = registration.getHospital() != null ? registration.getHospital().getName() : null;
            UUID patientId = registration.getPatient() != null ? registration.getPatient().getId() : null;
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userName(SecurityUtils.getCurrentUsername())
                    .userId(actorId)
                    .assignmentId(assignment != null ? assignment.getId() : null)
                    .roleName(assignment != null && assignment.getRole() != null ? assignment.getRole().getName() : null)
                    .hospitalName(hospitalName)
                    .patientId(patientId)
                    .eventType(AuditEventType.DATA_ACCESS)
                    .entityType("PATIENT_UPLOADED_DOCUMENT")
                    .resourceId(doc.getId() != null ? doc.getId().toString() : null)
                    .eventDescription("Staff download of patient-uploaded document " + doc.getId()
                            + " (" + doc.getDocumentType() + ")")
                    .status(AuditStatus.SUCCESS)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Audit emit failed for staff document download {}: {}", doc.getId(), ex.getMessage());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Ownership-scoped lookup shared by get/delete/download: only a
     * live (non-deleted) document belonging to the calling patient
     * resolves; anything else is a 404.
     */
    private PatientUploadedDocument requireOwnDocument(UUID patientId, UUID documentId) {
        return documentRepository
                .findByIdAndPatient_IdAndDeletedAtIsNull(documentId, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
    }

    private UUID resolveUserId(Authentication auth) {
        return authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
    }

    private UUID resolvePatientId(Authentication auth) {
        UUID userId = resolveUserId(auth);
        return patientRepository.findByUserId(userId)
                .map(Patient::getId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No patient record linked to your account. Contact your care team."));
    }

    private Patient resolvePatient(UUID userId) {
        return patientRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No patient record linked to your account. Contact your care team."));
    }

    private User resolveUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }
}
