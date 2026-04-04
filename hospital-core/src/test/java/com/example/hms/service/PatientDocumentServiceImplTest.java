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
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.impl.PatientDocumentServiceImpl;
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
import org.springframework.security.core.Authentication;

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
}
