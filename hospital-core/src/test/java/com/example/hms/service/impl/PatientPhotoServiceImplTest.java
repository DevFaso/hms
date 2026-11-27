package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientPhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Patient photos (P3 #21): PHI binaries kept OUT of the permitAll
 * /uploads/** tree, streamed only through the authenticated endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientPhotoServiceImplTest {

    @Mock private PatientRepository patientRepository;

    @TempDir
    Path tempDir;

    private PatientPhotoServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        service = new PatientPhotoServiceImpl(patientRepository, new com.example.hms.storage.LocalFileStorage(tempDir));

        hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        patientId = UUID.randomUUID();
        patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(patientId);
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        patient.setHospitalRegistrations(Set.of(registration));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(patientRepository.save(any(Patient.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** A JPEG by its signature, ending with the given bytes; empty stays empty. */
    private MockMultipartFile jpeg(String name, byte[] bytes) {
        if (bytes.length == 0) {
            return new MockMultipartFile("file", name, "image/jpeg", bytes);
        }
        byte[] signature = com.example.hms.testsupport.UploadSamples.jpeg();
        byte[] content = java.util.Arrays.copyOf(signature, signature.length + bytes.length);
        System.arraycopy(bytes, 0, content, signature.length, bytes.length);
        return new MockMultipartFile("file", name, "image/jpeg", content);
    }

    @Test
    void uploadStoresTheFileAndStampsThePatient() throws Exception {
        LocalDateTime updatedAt =
            service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1, 2, 3}));

        assertThat(updatedAt).isNotNull();
        assertThat(patient.getPhotoFilePath()).isNotBlank();
        assertThat(patient.getPhotoContentType()).isEqualTo("image/jpeg");
        assertThat(Files.exists(tempDir.resolve("patient-photos").resolve(patient.getPhotoFilePath()))).isTrue();
    }

    @Test
    void replacingDeletesThePreviousFile() throws Exception {
        service.upload(patientId, hospitalId, jpeg("one.jpg", new byte[] {1}));
        String firstFile = patient.getPhotoFilePath();

        service.upload(patientId, hospitalId, jpeg("two.jpg", new byte[] {2}));

        assertThat(patient.getPhotoFilePath()).isNotEqualTo(firstFile);
        assertThat(Files.exists(tempDir.resolve("patient-photos").resolve(firstFile))).isFalse();
        assertThat(Files.exists(tempDir.resolve("patient-photos").resolve(patient.getPhotoFilePath()))).isTrue();
    }

    @Test
    void rapidReplacementsNeverCollideAndNeverLoseTheStoredFile() {
        // The bug this pins. Filenames were built from
        // System.currentTimeMillis(), so replacing a photo inside the same
        // millisecond as the previous one produced an identical name: the copy
        // wrote the file and deleteQuietly(previousPath) then deleted the very
        // file it had just written, leaving photoFilePath pointing at nothing.
        // A double-clicked upload button is enough to reach it.
        //
        // replacingDeletesThePreviousFile above only caught this by luck —
        // whether two uploads land in the same millisecond is a matter of
        // timing, which is exactly why it failed intermittently in a full
        // suite run and passed on its own. Twenty-five uploads in a tight loop
        // make the collision near-certain with a millisecond clock and
        // impossible with a random suffix.
        Set<String> namesSeen = new HashSet<>();

        for (int i = 0; i < 25; i++) {
            service.upload(patientId, hospitalId, jpeg("photo" + i + ".jpg", new byte[] {(byte) i}));

            String stored = patient.getPhotoFilePath();
            assertThat(namesSeen.add(stored))
                .as("upload %s reused the filename %s", i, stored)
                .isTrue();
            // The invariant that actually matters: the record never points at
            // a file that is not there.
            assertThat(Files.exists(tempDir.resolve("patient-photos").resolve(stored)))
                .as("upload %s left photoFilePath pointing at a missing file", i)
                .isTrue();
        }
    }

    @Test
    void uploadRefusesNonImageContent() {
        MockMultipartFile pdf =
            new MockMultipartFile("file", "doc.pdf", "image/jpeg", com.example.hms.testsupport.UploadSamples.pdf());

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, pdf))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.typeUnsupported");
    }

    @Test
    void uploadRefusesADisallowedExtension() {
        MockMultipartFile svg =
            new MockMultipartFile("file", "pic.svg", "image/svg+xml", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, svg))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.typeUnsupported");
    }

    @Test
    void uploadRefusesAnUnregisteredPatient() {
        UUID foreignScope = UUID.randomUUID();
        MockMultipartFile file = jpeg("face.jpg", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, foreignScope, file))
            // QA D1870: "not here" is "not found", like GET /patients/{id}, never a distinct refusal.
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    void loadStreamsTheStoredBytes() {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {9, 9, 9}));

        PatientPhotoService.PhotoPayload payload = service.load(patientId, hospitalId);

        assertThat(payload.bytes()).endsWith(new byte[] {9, 9, 9});
        assertThat(payload.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void loadIs404WhenNoPhotoExists() {
        assertThatThrownBy(() -> service.load(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "patientPhoto.notFound");
    }

    @Test
    void loadIs404ForAScopedCallerWithoutRegistration() {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));
        UUID foreignScope = UUID.randomUUID();

        // 404-not-403: an unregistered patient looks exactly like a missing one.
        assertThatThrownBy(() -> service.load(patientId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "patient.notFound");
    }

    @Test
    void uploadRequiresAHospitalScope() {
        MockMultipartFile file = jpeg("face.jpg", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, null, file))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.hospitalRequired");
    }

    @Test
    void uploadRefusesAnEmptyFile() {
        MockMultipartFile empty = jpeg("face.jpg", new byte[] {});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, empty))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.required");
    }

    @Test
    void uploadRefusesAnOversizedFile() {
        MockMultipartFile big = jpeg("face.jpg", new byte[5 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, big))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.tooLarge");
    }

    @Test
    void uploadRefusesAFileWithoutAName() {
        MockMultipartFile nameless =
            new MockMultipartFile("file", null, "image/jpeg", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, nameless))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.typeUnsupported");
    }

    @Test
    void deleteRequiresAHospitalScope() {
        assertThatThrownBy(() -> service.delete(patientId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("patient.photo.hospitalRequiredRemove");
    }

    @Test
    void loadIs404WhenTheStoredFileVanishedFromDisk() throws Exception {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));
        Files.delete(tempDir.resolve("patient-photos").resolve(patient.getPhotoFilePath()));

        // The DB row points at a file an operator removed — same outcome as
        // no photo, never a 500.
        assertThatThrownBy(() -> service.load(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "patientPhoto.notFound");
    }

    @Test
    void deleteClearsColumnsAndRemovesTheFile() {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));
        String storedFile = patient.getPhotoFilePath();

        service.delete(patientId, hospitalId);

        assertThat(patient.getPhotoFilePath()).isNull();
        assertThat(patient.getPhotoContentType()).isNull();
        assertThat(patient.getPhotoUpdatedAt()).isNull();
        assertThat(Files.exists(tempDir.resolve("patient-photos").resolve(storedFile))).isFalse();
    }

    // ── Type by content, the upload record, quarantine ────────────────────

    @Test
    void anSvgIsRefusedAsItselfAndDressedAsAnImage() {
        byte[] svg = com.example.hms.testsupport.UploadSamples.svg();

        assertThatThrownBy(() -> service.upload(patientId, hospitalId,
                new MockMultipartFile("file", "face.svg", "image/svg+xml", svg)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "patient.photo.typeUnsupported");
        assertThatThrownBy(() -> service.upload(patientId, hospitalId,
                new MockMultipartFile("file", "face.png", "image/png", svg)))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.contentMismatch");
        assertThat(patient.getPhotoFilePath()).isNull();
    }

    @Test
    void theStoredTypeIsTheOneTheContentShowsNotTheDeclaredOne() {
        service.upload(patientId, hospitalId, new MockMultipartFile("file", "face.png", "image/svg+xml",
            com.example.hms.testsupport.UploadSamples.png()));

        assertThat(patient.getPhotoContentType()).isEqualTo("image/png");
        assertThat(service.load(patientId, hospitalId).contentType()).isEqualTo("image/png");
    }

    @Test
    void aStoredSvgIsNeverServed() throws Exception {
        // A row written before the content check: an SVG, with the type its client declared.
        Files.createDirectories(tempDir.resolve("patient-photos"));
        Files.write(tempDir.resolve("patient-photos").resolve("legacy_photo.svg"),
            com.example.hms.testsupport.UploadSamples.svg());
        patient.setPhotoFilePath("legacy_photo.svg");
        patient.setPhotoContentType("image/svg+xml");

        assertThatThrownBy(() -> service.load(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "patientPhoto.notFound");
    }

    @Test
    void aLegacyRowIsServedWithTheTypeOfItsBytes() throws Exception {
        Files.createDirectories(tempDir.resolve("patient-photos"));
        Files.write(tempDir.resolve("patient-photos").resolve("legacy_photo.jpg"),
            com.example.hms.testsupport.UploadSamples.png());
        patient.setPhotoFilePath("legacy_photo.jpg");
        patient.setPhotoContentType("text/html");

        assertThat(service.load(patientId, hospitalId).contentType()).isEqualTo("image/png");
    }

    @Test
    void thePhotoIsRecordedForItsHospitalAndAnInfectedOneIsRefused() {
        com.example.hms.upload.UploadLedger ledger = org.mockito.Mockito.mock(com.example.hms.upload.UploadLedger.class);
        PatientPhotoServiceImpl recorded = new PatientPhotoServiceImpl(patientRepository,
            new com.example.hms.storage.LocalFileStorage(tempDir), ledger);
        when(ledger.record(any())).thenReturn(com.example.hms.upload.UploadScanStatus.CLEAN);

        recorded.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));

        org.mockito.ArgumentCaptor<com.example.hms.upload.UploadLedger.StoredUpload> upload =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.upload.UploadLedger.StoredUpload.class);
        org.mockito.Mockito.verify(ledger).record(upload.capture());
        assertThat(upload.getValue().kind()).isEqualTo(com.example.hms.upload.UploadKind.PATIENT_PHOTO);
        assertThat(upload.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(upload.getValue().hospitalId()).isEqualTo(hospitalId);
        assertThat(upload.getValue().storageKey()).isEqualTo("patient-photos/" + patient.getPhotoFilePath());
        String kept = patient.getPhotoFilePath();

        when(ledger.record(any())).thenReturn(com.example.hms.upload.UploadScanStatus.INFECTED);
        assertThatThrownBy(() -> recorded.upload(patientId, hospitalId, jpeg("bad.jpg", new byte[] {2})))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.upload.blocked");
        // The patient keeps the photo they had.
        assertThat(patient.getPhotoFilePath()).isEqualTo(kept);

        org.mockito.Mockito.doThrow(new ResourceNotFoundException("file.upload.pendingCheck"))
            .when(ledger).requireServable("patient-photos/" + kept);
        assertThatThrownBy(() -> recorded.load(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.upload.pendingCheck");
    }
}
