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
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.FileUploadService;
import com.example.hms.service.PatientDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
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

    @Override
    @Transactional
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
        UUID patientId = resolvePatientId(auth);
        PatientUploadedDocument doc = documentRepository
                .findByIdAndPatient_IdAndDeletedAtIsNull(documentId, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        return documentMapper.toDto(doc);
    }

    @Override
    @Transactional
    public void deleteDocument(Authentication auth, UUID documentId) {
        UUID patientId = resolvePatientId(auth);
        PatientUploadedDocument doc = documentRepository
                .findByIdAndPatient_IdAndDeletedAtIsNull(documentId, patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        doc.setDeletedAt(LocalDateTime.now());
        documentRepository.save(doc);
        log.info("Patient {} soft-deleted document {}", patientId, documentId);
    }

    // ── Private helpers ──────────────────────────────────────────────────

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
