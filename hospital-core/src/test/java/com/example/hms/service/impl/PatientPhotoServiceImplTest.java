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
        service = new PatientPhotoServiceImpl(patientRepository, tempDir.toString());

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

    private MockMultipartFile jpeg(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/jpeg", bytes);
    }

    @Test
    void uploadStoresTheFileAndStampsThePatient() throws Exception {
        LocalDateTime updatedAt =
            service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1, 2, 3}));

        assertThat(updatedAt).isNotNull();
        assertThat(patient.getPhotoFilePath()).isNotBlank();
        assertThat(patient.getPhotoContentType()).isEqualTo("image/jpeg");
        assertThat(Files.exists(tempDir.resolve(patient.getPhotoFilePath()))).isTrue();
    }

    @Test
    void replacingDeletesThePreviousFile() throws Exception {
        service.upload(patientId, hospitalId, jpeg("one.jpg", new byte[] {1}));
        String firstFile = patient.getPhotoFilePath();

        service.upload(patientId, hospitalId, jpeg("two.jpg", new byte[] {2}));

        assertThat(patient.getPhotoFilePath()).isNotEqualTo(firstFile);
        assertThat(Files.exists(tempDir.resolve(firstFile))).isFalse();
        assertThat(Files.exists(tempDir.resolve(patient.getPhotoFilePath()))).isTrue();
    }

    @Test
    void rapidReplacementsNeverCollideAndNeverLoseTheStoredFile() throws Exception {
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
            assertThat(Files.exists(tempDir.resolve(stored)))
                .as("upload %s left photoFilePath pointing at a missing file", i)
                .isTrue();
        }
    }

    @Test
    void uploadRefusesNonImageContent() {
        MockMultipartFile pdf =
            new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, pdf))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only image files");
    }

    @Test
    void uploadRefusesADisallowedExtension() {
        MockMultipartFile svg =
            new MockMultipartFile("file", "pic.svg", "image/svg+xml", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, svg))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported image type");
    }

    @Test
    void uploadRefusesAnUnregisteredPatient() {
        UUID foreignScope = UUID.randomUUID();
        MockMultipartFile file = jpeg("face.jpg", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, foreignScope, file))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not registered");
    }

    @Test
    void loadStreamsTheStoredBytes() {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {9, 9, 9}));

        PatientPhotoService.PhotoPayload payload = service.load(patientId, hospitalId);

        assertThat(payload.bytes()).containsExactly(9, 9, 9);
        assertThat(payload.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void loadIs404WhenNoPhotoExists() {
        assertThatThrownBy(() -> service.load(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("No photo on file");
    }

    @Test
    void loadIs404ForAScopedCallerWithoutRegistration() {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));
        UUID foreignScope = UUID.randomUUID();

        // 404-not-403: an unregistered patient looks exactly like a missing one.
        assertThatThrownBy(() -> service.load(patientId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Patient not found");
    }

    @Test
    void uploadRequiresAHospitalScope() {
        MockMultipartFile file = jpeg("face.jpg", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, null, file))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void uploadRefusesAnEmptyFile() {
        MockMultipartFile empty = jpeg("face.jpg", new byte[] {});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, empty))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("photo file is required");
    }

    @Test
    void uploadRefusesAnOversizedFile() {
        MockMultipartFile big = jpeg("face.jpg", new byte[5 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, big))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("5 MB");
    }

    @Test
    void uploadRefusesAFileWithoutAName() {
        MockMultipartFile nameless =
            new MockMultipartFile("file", null, "image/jpeg", new byte[] {1});

        assertThatThrownBy(() -> service.upload(patientId, hospitalId, nameless))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unsupported image type");
    }

    @Test
    void deleteRequiresAHospitalScope() {
        assertThatThrownBy(() -> service.delete(patientId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void loadIs404WhenTheStoredFileVanishedFromDisk() throws Exception {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));
        Files.delete(tempDir.resolve(patient.getPhotoFilePath()));

        // The DB row points at a file an operator removed — same outcome as
        // no photo, never a 500.
        assertThatThrownBy(() -> service.load(patientId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("No photo on file");
    }

    @Test
    void deleteClearsColumnsAndRemovesTheFile() {
        service.upload(patientId, hospitalId, jpeg("face.jpg", new byte[] {1}));
        String storedFile = patient.getPhotoFilePath();

        service.delete(patientId, hospitalId);

        assertThat(patient.getPhotoFilePath()).isNull();
        assertThat(patient.getPhotoContentType()).isNull();
        assertThat(patient.getPhotoUpdatedAt()).isNull();
        assertThat(Files.exists(tempDir.resolve(storedFile))).isFalse();
    }
}
