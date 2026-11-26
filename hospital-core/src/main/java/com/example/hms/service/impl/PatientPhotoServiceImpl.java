package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientPhotoService;
import com.example.hms.storage.FileStorage;
import com.example.hms.storage.StorageKeys;
import com.example.hms.storage.StoredObject;
import com.example.hms.upload.ContentSniffer;
import com.example.hms.upload.UploadAdmission;
import com.example.hms.upload.UploadAdmission.VettedUpload;
import com.example.hms.upload.UploadKind;
import com.example.hms.upload.UploadLedger;
import com.example.hms.upload.UploadScanStatus;
import com.example.hms.upload.UploadType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Patient photos (P3 #21), kept in {@link FileStorage} under
 * {@code patient-photos/<name>}: the directory they always had under the
 * upload root (QA D1184), so the names stored on patient rows resolve in the
 * local directory and in the bucket alike. A patient photo leaves the server
 * only through the authenticated streaming endpoint: the only stored class
 * served without a token is profile images ({@code ProfileImageController},
 * SecurityConfig). The patient row stores the bare file name.
 *
 * <p>A photo is a JPEG, PNG, GIF or WebP by its own bytes ({@link UploadAdmission}),
 * 5 MB at most; the type stored on the patient row is the one the content
 * showed. What is read back is checked again: bytes that are not one of those
 * images (a row stored before the content check) are not served.
 */
@Slf4j
@Service
@Transactional
public class PatientPhotoServiceImpl implements PatientPhotoService {

    /** The storage prefix of patient photos: the subdirectory they had under the upload root. */
    static final String PHOTO_PREFIX = "patient-photos/";

    private final PatientRepository patientRepository;
    private final FileStorage storage;
    /** Null only in a unit test that builds the service on a bare store. */
    private final UploadLedger ledger;

    @org.springframework.beans.factory.annotation.Autowired
    public PatientPhotoServiceImpl(PatientRepository patientRepository, FileStorage storage, UploadLedger ledger) {
        this.patientRepository = patientRepository;
        this.storage = storage;
        this.ledger = ledger;
    }

    /** For tests of the storage paths alone: no upload record, no malware check. */
    PatientPhotoServiceImpl(PatientRepository patientRepository, FileStorage storage) {
        this(patientRepository, storage, null);
    }

    @Override
    public LocalDateTime upload(UUID patientId, UUID hospitalId, MultipartFile file) {
        if (hospitalId == null) {
            throw new BusinessException("patient.photo.hospitalRequired");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            // QA D1870 (round 53): a patient not registered here is "not found", like GET /patients/{id};
            // a distinct answer told a caller holding the id that the patient exists at another hospital.
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }
        if (file == null || file.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("file", "patient.photo.required");
        }
        VettedUpload vetted = UploadAdmission.vet(file, UploadKind.PATIENT_PHOTO);

        String extension = vetted.extension();
        // A random suffix, not a timestamp. This was
        // System.currentTimeMillis(), which collides whenever a photo is
        // replaced inside the same millisecond as the one before it -- a
        // double-clicked upload is enough. The new name then equalled the old,
        // so the copy below wrote the file and deleteQuietly(previousPath)
        // deleted the very file it had just written, leaving photoFilePath
        // pointing at nothing.
        //
        // Uniqueness is the whole guarantee, deliberately with nothing behind
        // it. A defensive comparison of the new name against the previous one
        // was written first and then removed: it can only fire on a UUID
        // collision, so it is a branch no test can reach, and an untestable
        // branch guarding an impossible case is worse than the invariant
        // stated plainly here and pinned by
        // rapidReplacementsNeverCollideAndNeverLoseTheStoredFile.
        String filename = patientId + "_photo_" + UUID.randomUUID() + extension;
        String key = keyOf(filename);
        if (key == null) {
            throw new BusinessException("patient.photo.invalidName");
        }
        try {
            try (InputStream in = file.getInputStream()) {
                storage.put(key, in, file.getSize(), vetted.mediaType());
            }
            if (ledger != null && ledger.record(new UploadLedger.StoredUpload(key, UploadKind.PATIENT_PHOTO,
                    vetted.mediaType(), file.getSize(), null, null, hospitalId)) == UploadScanStatus.INFECTED) {
                // Kept, unserved; the patient keeps the photo they had.
                throw new BusinessException("file.upload.blocked");
            }

            String previousPath = patient.getPhotoFilePath();
            LocalDateTime now = LocalDateTime.now();
            patient.setPhotoFilePath(filename);
            patient.setPhotoContentType(vetted.mediaType());
            patient.setPhotoUpdatedAt(now);
            patientRepository.save(patient);
            deleteQuietly(previousPath);
            return now;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the patient photo", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PhotoPayload load(UUID patientId, UUID hospitalId) {
        Patient patient = loadScoped(patientId, hospitalId);
        String storedName = patient.getPhotoFilePath();
        if (storedName == null || storedName.isBlank()) {
            throw new ResourceNotFoundException("patientPhoto.notFound", patientId);
        }
        String key = keyOf(storedName);
        if (key == null) {
            throw new ResourceNotFoundException("patientPhoto.notFound", patientId);
        }
        if (ledger != null) {
            ledger.requireServable(key);
        }
        try {
            Optional<StoredObject> stored = storage.get(key);
            if (stored.isEmpty()) {
                // QA D1184 (round 22): recorded but no longer stored.
                log.warn("Patient photo file missing in {}", storage.describe());
                throw new ResourceNotFoundException("patientPhoto.notFound", patientId);
            }
            try (StoredObject object = stored.get()) {
                byte[] bytes = object.content().readAllBytes();
                // The type comes from the bytes, never from the row: a row written before the
                // content check may hold whatever type a client declared.
                byte[] head = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, ContentSniffer.HEAD_BYTES));
                UploadType type = ContentSniffer.detect(head).filter(UploadType::isRasterImage)
                    .orElseThrow(() -> new ResourceNotFoundException("patientPhoto.notFound", patientId));
                return new PhotoPayload(bytes, type.mediaType());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the patient photo", e);
        }
    }

    @Override
    public void delete(UUID patientId, UUID hospitalId) {
        if (hospitalId == null) {
            throw new BusinessException("patient.photo.hospitalRequiredRemove");
        }
        Patient patient = loadScoped(patientId, hospitalId);
        String storedName = patient.getPhotoFilePath();
        patient.setPhotoFilePath(null);
        patient.setPhotoContentType(null);
        patient.setPhotoUpdatedAt(null);
        patientRepository.save(patient);
        deleteQuietly(storedName);
    }

    /* ── Guards ────────────────────────────────────────────────────────── */

    private Patient loadScoped(UUID patientId, UUID hospitalId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));
        // 404-not-403: an unregistered patient looks exactly like a missing one.
        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }
        return patient;
    }

    /** The storage key of a stored photo name, or {@code null} when the name is not a plain file name. */
    private static String keyOf(String storedName) {
        if (storedName.contains("/")) {
            return null;
        }
        try {
            return StorageKeys.requireValid(PHOTO_PREFIX + storedName);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void deleteQuietly(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return;
        }
        String key = keyOf(storedName);
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Failed to delete previous patient photo {}: {}", storedName, e.getMessage());
        }
    }
}
