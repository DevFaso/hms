package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientDocumentMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.PatientDocumentRequestDTO;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.impl.PatientDocumentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientDocumentServiceImplTest {

    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientRepository patientRepository;
    @Mock private UserRepository userRepository;
    @Mock private PatientUploadedDocumentRepository documentRepository;
    @Mock private FileUploadService fileUploadService;
    @Mock private PatientDocumentMapper documentMapper;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private Authentication auth;

    @InjectMocks
    private PatientDocumentServiceImpl service;

    private final UUID userId    = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private Patient   patient;
    private User      user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(userId);
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUsername("jane.doe");

        patient = new Patient();
        patient.setId(patientId);
        patient.setUser(user);
        patient.setFirstName("Jane");
        patient.setLastName("Doe");

        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
    }

    // ── uploadDocument ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("uploadDocument()")
    class UploadDocument {

        @Test
        @DisplayName("stores file and returns DTO on success")
        void uploadsSuccessfully() throws IOException {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf", "PDF content".getBytes());

            PatientDocumentRequestDTO request = PatientDocumentRequestDTO.builder()
                    .documentType(PatientDocumentType.LAB_RESULT)
                    .collectionDate(LocalDate.of(2026, 3, 1))
                    .notes("External lab result")
                    .build();

            FileUploadService.StoredFileDescriptor descriptor = new FileUploadService.StoredFileDescriptor(
                    "/uploads/patient-documents/report.pdf",
                    "http://localhost/uploads/patient-documents/report.pdf",
                    "report.pdf", "application/pdf", 11L, "abc123");

            PatientUploadedDocument savedDoc = PatientUploadedDocument.builder()
                    .patient(patient)
                    .uploadedByUser(user)
                    .documentType(PatientDocumentType.LAB_RESULT)
                    .displayName("report.pdf")
                    .filePath("/uploads/patient-documents/report.pdf")
                    .build();
            savedDoc.setId(UUID.randomUUID());

            PatientDocumentResponseDTO expectedDto = PatientDocumentResponseDTO.builder()
                    .id(savedDoc.getId())
                    .documentType(PatientDocumentType.LAB_RESULT)
                    .displayName("report.pdf")
                    .build();

            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(fileUploadService.uploadPatientDocument(file, userId)).thenReturn(descriptor);
            when(documentRepository.save(any())).thenReturn(savedDoc);
            when(documentMapper.toDto(savedDoc)).thenReturn(expectedDto);

            PatientDocumentResponseDTO result = service.uploadDocument(auth, file, request);

            assertThat(result).isEqualTo(expectedDto);
            ArgumentCaptor<PatientUploadedDocument> captor = ArgumentCaptor.forClass(PatientUploadedDocument.class);
            verify(documentRepository).save(captor.capture());
            assertThat(captor.getValue().getDocumentType()).isEqualTo(PatientDocumentType.LAB_RESULT);
            assertThat(captor.getValue().getNotes()).isEqualTo("External lab result");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when no patient linked to user")
        void throwsWhenPatientNotFound() {
            MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", new byte[0]);
            PatientDocumentRequestDTO request = PatientDocumentRequestDTO.builder()
                    .documentType(PatientDocumentType.OTHER).build();

            when(patientRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.uploadDocument(auth, file, request))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── listDocuments ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listDocuments()")
    class ListDocuments {

        @Test
        @DisplayName("lists all documents when no type filter is provided")
        void listsAllDocuments() {
            Pageable pageable = PageRequest.of(0, 20);
            PatientUploadedDocument doc = PatientUploadedDocument.builder()
                    .documentType(PatientDocumentType.IMAGING_REPORT).build();
            doc.setId(UUID.randomUUID());

            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByPatient_IdAndDeletedAtIsNull(patientId, pageable))
                    .thenReturn(new PageImpl<>(List.of(doc)));
            when(documentMapper.toDto(doc)).thenReturn(new PatientDocumentResponseDTO());

            Page<PatientDocumentResponseDTO> result = service.listDocuments(auth, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(documentRepository).findByPatient_IdAndDeletedAtIsNull(patientId, pageable);
        }

        @Test
        @DisplayName("filters by document type when type is specified")
        void filtersbyType() {
            Pageable pageable = PageRequest.of(0, 20);
            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(
                    patientId, PatientDocumentType.LAB_RESULT, pageable))
                    .thenReturn(new PageImpl<>(List.of()));

            Page<PatientDocumentResponseDTO> result = service.listDocuments(auth, PatientDocumentType.LAB_RESULT, pageable);

            assertThat(result).isEmpty();
            verify(documentRepository).findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(
                    patientId, PatientDocumentType.LAB_RESULT, pageable);
        }
    }

    // ── getDocument ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getDocument()")
    class GetDocument {

        @Test
        @DisplayName("returns DTO when document belongs to patient")
        void returnsDocument() {
            UUID docId = UUID.randomUUID();
            PatientUploadedDocument doc = PatientUploadedDocument.builder()
                    .documentType(PatientDocumentType.INVOICE).build();
            doc.setId(docId);

            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.of(doc));
            when(documentMapper.toDto(doc)).thenReturn(new PatientDocumentResponseDTO());

            PatientDocumentResponseDTO result = service.getDocument(auth, docId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when document not found")
        void throwsWhenNotFound() {
            UUID docId = UUID.randomUUID();
            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDocument(auth, docId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(docId.toString());
        }
    }

    // ── deleteDocument ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteDocument()")
    class DeleteDocument {

        @Test
        @DisplayName("sets deletedAt timestamp and saves")
        void softDeletes() {
            UUID docId = UUID.randomUUID();
            PatientUploadedDocument doc = PatientUploadedDocument.builder()
                    .documentType(PatientDocumentType.OTHER).build();
            doc.setId(docId);

            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.of(doc));
            when(documentRepository.save(any())).thenReturn(doc);

            service.deleteDocument(auth, docId);

            ArgumentCaptor<PatientUploadedDocument> captor = ArgumentCaptor.forClass(PatientUploadedDocument.class);
            verify(documentRepository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull()
                    .isBefore(LocalDateTime.now().plusSeconds(1));
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when document not found")
        void throwsWhenNotFound() {
            UUID docId = UUID.randomUUID();
            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteDocument(auth, docId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── downloadDocument (authenticated streaming — the /uploads fix) ────────

    @Nested
    @DisplayName("downloadDocument()")
    class DownloadDocument {

        private final UUID docId = UUID.randomUUID();

        @Test
        @DisplayName("streams the patient's own document with its stored headers")
        void streamsOwnDocument() {
            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            PatientUploadedDocument doc = PatientUploadedDocument.builder()
                    .patient(patient)
                    .filePath("/uploads/patient-documents/x.pdf")
                    .mimeType("application/pdf")
                    .displayName("lab-report.pdf")
                    .build();
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.of(doc));
            java.nio.file.Path onDisk = java.nio.file.Path.of("x.pdf");
            when(fileUploadService.resolveStoredFile("/uploads/patient-documents/x.pdf", "patient-documents"))
                    .thenReturn(onDisk);

            PatientDocumentService.DocumentPayload payload = service.downloadDocument(auth, docId);

            assertThat(payload.path()).isEqualTo(onDisk);
            assertThat(payload.contentType()).isEqualTo("application/pdf");
            assertThat(payload.displayName()).isEqualTo("lab-report.pdf");
        }

        @Test
        @DisplayName("another patient's document reads as not-found")
        void foreignDocumentIsNotFound() {
            // The ownership filter is in the query itself: a document id
            // belonging to a different patient simply never comes back.
            when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.downloadDocument(auth, docId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Staff surface ────────────────────────────────────────────────────────
    // Reads through the chart: gated on the caller's active hospital and the
    // patient's registration there. The patient-side ownership helpers above
    // are not involved — the caller is staff, not the patient.

    @Nested
    @DisplayName("staff surface: listForPatient / getForPatient / downloadForPatient")
    class StaffSurface {

        private final UUID hospitalId = UUID.randomUUID();
        private final UUID docId = UUID.randomUUID();
        private final UUID staffId = UUID.randomUUID();

        @BeforeEach
        void staffCaller() {
            // The outer setUp stubs the patient-side caller; staff reads never
            // touch it, and MockitoExtension is strict about unused stubs.
            org.mockito.Mockito.reset(authUtils);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("dr.who", "n/a", List.of()));
            lenient().when(authUtils.resolveUserId(any())).thenReturn(Optional.of(staffId));
        }

        @AfterEach
        void clearCaller() {
            SecurityContextHolder.clearContext();
        }

        private void registered() {
            when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
                    .thenReturn(Optional.of(new PatientHospitalRegistration()));
        }

        @Test
        @DisplayName("no active hospital (super-admin global view) is a 400 naming the fix, not an NPE")
        void noHospitalIsABusinessException() {
            assertThatThrownBy(() -> service.listForPatient(null, patientId, null, PageRequest.of(0, 20)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("active hospital is required");
            assertThatThrownBy(() -> service.downloadForPatient(null, patientId, docId))
                    .isInstanceOf(BusinessException.class);
            verify(documentRepository, never()).findByPatient_IdAndDeletedAtIsNull(any(), any());
        }

        @Test
        @DisplayName("a patient not registered at the caller's hospital reads as not-found, not forbidden")
        void unregisteredPatientIsNotFound() {
            when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listForPatient(hospitalId, patientId, null, PageRequest.of(0, 20)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.getForPatient(hospitalId, patientId, docId))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.downloadForPatient(hospitalId, patientId, docId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(documentRepository, never()).findByIdAndPatient_IdAndDeletedAtIsNull(any(), any());
            verify(auditEventLogService, never()).logEvent(any());
        }

        @Test
        @DisplayName("lists live documents for a registered patient, optionally by type")
        void listsForRegisteredPatient() {
            registered();
            Pageable pageable = PageRequest.of(0, 20);
            PatientUploadedDocument doc = PatientUploadedDocument.builder().patient(patient).build();
            PatientDocumentResponseDTO dto = new PatientDocumentResponseDTO();
            when(documentRepository.findByPatient_IdAndDeletedAtIsNull(patientId, pageable))
                    .thenReturn(new PageImpl<>(List.of(doc)));
            when(documentRepository.findByPatient_IdAndDocumentTypeAndDeletedAtIsNull(
                    patientId, PatientDocumentType.REFERRAL_LETTER, pageable))
                    .thenReturn(new PageImpl<>(List.of(doc, doc)));
            when(documentMapper.toDto(doc)).thenReturn(dto);

            assertThat(service.listForPatient(hospitalId, patientId, null, pageable).getContent()).hasSize(1);
            assertThat(service.listForPatient(hospitalId, patientId, PatientDocumentType.REFERRAL_LETTER, pageable)
                    .getContent()).hasSize(2);
        }

        @Test
        @DisplayName("download streams the stored file and writes a DATA_ACCESS row naming the document, not the file name")
        void downloadStreamsAndAudits() {
            registered();
            PatientUploadedDocument doc = PatientUploadedDocument.builder()
                    .patient(patient)
                    .documentType(PatientDocumentType.LAB_RESULT)
                    .filePath("/uploads/patient-documents/y.pdf")
                    .mimeType("application/pdf")
                    .displayName("hiv-results.pdf")
                    .build();
            doc.setId(docId);
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.of(doc));
            java.nio.file.Path onDisk = java.nio.file.Path.of("y.pdf");
            when(fileUploadService.resolveStoredFile("/uploads/patient-documents/y.pdf", "patient-documents"))
                    .thenReturn(onDisk);

            PatientDocumentService.DocumentPayload payload = service.downloadForPatient(hospitalId, patientId, docId);

            assertThat(payload.path()).isEqualTo(onDisk);
            assertThat(payload.contentType()).isEqualTo("application/pdf");
            assertThat(payload.displayName()).isEqualTo("hiv-results.pdf");

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(audit.capture());
            AuditEventRequestDTO row = audit.getValue();
            assertThat(row.getUserId()).isEqualTo(staffId);
            assertThat(row.getUserName()).isEqualTo("dr.who");
            assertThat(row.getEventType()).isEqualTo(AuditEventType.DATA_ACCESS);
            assertThat(row.getStatus()).isEqualTo(AuditStatus.SUCCESS);
            assertThat(row.getPatientId()).isEqualTo(patientId);
            assertThat(row.getResourceId()).isEqualTo(docId.toString());
            assertThat(row.getEntityType()).isEqualTo("PatientUploadedDocument");
            // The patient-chosen file name can itself be PHI; the row must not carry it.
            assertThat(row.getEventDescription()).contains(docId.toString()).contains("LAB_RESULT")
                    .doesNotContain("hiv-results");
        }

        @Test
        @DisplayName("a deleted or foreign document reads as not-found and is not audited")
        void missingDocumentIsNotFoundAndNotAudited() {
            registered();
            when(documentRepository.findByIdAndPatient_IdAndDeletedAtIsNull(docId, patientId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.downloadForPatient(hospitalId, patientId, docId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(auditEventLogService, never()).logEvent(any());
        }
    }
}
