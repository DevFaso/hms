package com.example.hms.service.impl;

import com.example.hms.service.support.LinkedPatientLookup;
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
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.model.Hospital;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
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

    private static final String MSG_UNABLE_RESOLVE_USER = "document.user.unresolved";

    private final ControllerAuthUtils authUtils;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final PatientUploadedDocumentRepository documentRepository;
    private final FileUploadService fileUploadService;
    private final PatientDocumentMapper documentMapper;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogService auditEventLogService;
    private final RecordAccessPolicy recordAccessPolicy;
    private final HospitalRepository hospitalRepository;

    /**
     * What authorised a staff read: the hospital name the audit row carries,
     * and the break-the-glass session when the read ran under one instead of
     * a registration at the acting hospital (QA D504).
     */
    private record StaffReach(String hospitalName, UUID breakGlassSessionId) {
    }

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
        if (request.getNotes() != null
                && request.getNotes().length() > PatientDocumentRequestDTO.NOTES_MAX_LENGTH) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw(
                    "documents.notes.tooLong", String.valueOf(PatientDocumentRequestDTO.NOTES_MAX_LENGTH)));
        }
        // QA D433: a document cannot have been collected in the future.
        if (request.getCollectionDate() != null
                && request.getCollectionDate().isAfter(java.time.LocalDate.now(java.time.ZoneId.systemDefault()))) {
            throw new IllegalArgumentException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw("portal.document.futureDate"));
        }

        // QA D961 review: a medical certificate is issued by a doctor from a
        // visit; a patient upload labelled as one would pass for it.
        if (request.getDocumentType() == com.example.hms.enums.PatientDocumentType.MEDICAL_CERTIFICATE) {
            throw new IllegalArgumentException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw("medicalCertificate.patientCannotUpload"));
        }
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
        // QA D961: a certificate the doctor issued is part of the record; the
        // patient downloads it but cannot remove it from the chart.
        if (doc.getDocumentType() == com.example.hms.enums.PatientDocumentType.MEDICAL_CERTIFICATE) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("medicalCertificate.patientCannotDelete"));
        }
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
        StaffReach reach = requireRegisteredAt(hospitalId, patientId);
        auditBreakGlassRead(hospitalId, patientId, reach, null);
        Page<PatientUploadedDocument> page;
        if (hospitalId == null) {
            page = documentType == null
                    ? documentRepository.findByPatient_IdAndDeletedAtIsNull(patientId, pageable)
                    : documentRepository.findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(patientId, documentType, pageable);
        } else {
            // QA D961: another hospital's certificate is not listed here.
            page = documentType == null
                    ? documentRepository.findForStaff(patientId, hospitalId, pageable)
                    : documentRepository.findForStaffByType(patientId, hospitalId, documentType, pageable);
        }
        return page.map(documentMapper::toDto);
    }

    /** QA D961: a document another hospital issued reads as missing here. */
    private static void requireIssuedHereOrUploaded(PatientUploadedDocument doc, UUID hospitalId, UUID documentId) {
        if (hospitalId != null && doc.getIssuingHospital() != null
                && !hospitalId.equals(doc.getIssuingHospital().getId())) {
            throw new ResourceNotFoundException("patientDocument.notFound", documentId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PatientDocumentResponseDTO getForPatient(UUID hospitalId, UUID patientId, UUID documentId) {
        StaffReach reach = requireRegisteredAt(hospitalId, patientId);
        PatientUploadedDocument doc = requireOwnDocument(patientId, documentId);
        requireIssuedHereOrUploaded(doc, hospitalId, documentId);
        auditBreakGlassRead(hospitalId, patientId, reach, doc);
        return documentMapper.toDto(doc);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentPayload downloadForPatient(UUID hospitalId, UUID patientId, UUID documentId) {
        StaffReach reach = requireRegisteredAt(hospitalId, patientId);
        PatientUploadedDocument doc = requireOwnDocument(patientId, documentId);
        requireIssuedHereOrUploaded(doc, hospitalId, documentId);
        java.nio.file.Path path = fileUploadService.resolveStoredFile(doc.getFilePath(), "patient-documents");
        auditStaffDownload(hospitalId, patientId, reach, doc);
        String contentType = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
        String displayName = doc.getDisplayName() != null ? doc.getDisplayName() : "document";
        return new DocumentPayload(path, contentType, displayName);
    }

    /**
     * Staff see a patient's uploads through a hospital the patient is
     * registered at, or (QA D504) under a live break-the-glass session the
     * actor declared for this patient at the acting hospital: the same
     * emergency access that opens the rest of the chart over an opt-out or a
     * restriction. No hospital (a super-admin in global view) is a 400 that
     * says so. A restricted chart is the loud 403 the chart itself answers,
     * so the portal offers the declaration. Any other refusal (an opt-out
     * without a session included) stays a 404, the same answer as a document
     * that does not exist, so the endpoint never confirms that a patient is
     * known elsewhere or has opted out.
     */
    private StaffReach requireRegisteredAt(UUID hospitalId, UUID patientId) {
        if (hospitalId == null) {
            throw new BusinessException("documents.hospitalRequired");
        }
        if (patientId == null) {
            // No id to name: "Patient not found with ID: null" helped nobody.
            throw new ResourceNotFoundException("patient.notFoundUnspecified");
        }
        java.util.Optional<PatientHospitalRegistration> registration =
                registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId);
        if (registration.isPresent()) {
            Hospital hospital = registration.get().getHospital();
            return new StaffReach(hospital != null ? hospital.getName() : null, null);
        }
        UUID actorId = authUtils.resolveUserId(SecurityContextHolder.getContext().getAuthentication())
                .orElse(null);
        RecordAccessDecision decision = actorId == null ? null
                : recordAccessPolicy.decide(actorId, patientId, hospitalId);
        if (decision != null && decision.permitted() && decision.relationship() != null
                && decision.relationship().kind() == TreatmentRelationshipKind.BREAK_GLASS) {
            String hospitalName = hospitalRepository.findById(hospitalId).map(Hospital::getName).orElse(null);
            return new StaffReach(hospitalName, decision.relationship().carrierId());
        }
        if (decision != null && decision.reason() == RecordAccessDenialReason.CHART_RESTRICTED) {
            throw new ChartRestrictedException(patientId);
        }
        throw new ResourceNotFoundException("patient.notFound", patientId);
    }

    /**
     * QA D504: a list or a view of the documents under break-the-glass is a
     * disclosure the patient was promised to see; a registered read is not
     * (the interceptor's PATIENT_ACCESS row covers it), so nothing is written.
     * The description names the session and at most the document id and type.
     */
    private void auditBreakGlassRead(UUID hospitalId, UUID patientId, StaffReach reach, PatientUploadedDocument doc) {
        if (reach.breakGlassSessionId() == null) {
            return;
        }
        String what = doc == null ? "Staff list of patient-uploaded documents"
                : "Staff view of patient-uploaded document " + doc.getId() + " (" + doc.getDocumentType() + ")";
        emitDocumentAudit(hospitalId, patientId, reach, doc, what);
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
    private void auditStaffDownload(UUID hospitalId, UUID patientId, StaffReach reach,
                                    PatientUploadedDocument doc) {
        emitDocumentAudit(hospitalId, patientId, reach, doc,
                "Staff download of patient-uploaded document " + doc.getId() + " (" + doc.getDocumentType() + ")");
    }

    private void emitDocumentAudit(UUID hospitalId, UUID patientId, StaffReach reach,
                                   PatientUploadedDocument doc, String what) {
        try {
            UUID actorId = authUtils.resolveUserId(SecurityContextHolder.getContext().getAuthentication())
                    .orElse(null);
            UserRoleHospitalAssignment assignment = actorId == null ? null
                    : assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId)
                            .orElse(null);
            String description = reach.breakGlassSessionId() == null ? what
                    : what + " under break-the-glass session " + reach.breakGlassSessionId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userName(SecurityUtils.getCurrentUsername())
                    .userId(actorId)
                    .assignmentId(assignment != null ? assignment.getId() : null)
                    .roleName(assignment != null && assignment.getRole() != null ? assignment.getRole().getName() : null)
                    .hospitalName(reach.hospitalName())
                    .patientId(patientId)
                    .eventType(AuditEventType.DATA_ACCESS)
                    .entityType("PATIENT_UPLOADED_DOCUMENT")
                    .resourceId(doc != null && doc.getId() != null ? doc.getId().toString() : null)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Audit emit failed for staff document read {}: {}", doc != null ? doc.getId() : null, ex.getMessage());
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
                .orElseThrow(() -> new ResourceNotFoundException("patientDocument.notFound", documentId));
    }

    private UUID resolveUserId(Authentication auth) {
        return authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
    }

    private UUID resolvePatientId(Authentication auth) {
        UUID userId = resolveUserId(auth);
        return LinkedPatientLookup.linkedPatient(patientRepository, userId)
                .map(Patient::getId)
                .orElseThrow(() -> new ResourceNotFoundException("patient.portal.noRecord"));
    }

    private Patient resolvePatient(UUID userId) {
        return LinkedPatientLookup.linkedPatient(patientRepository, userId)
                .orElseThrow(() -> new ResourceNotFoundException("patient.portal.noRecord"));
    }

    private User resolveUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
    }
}
