package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientPhotoService;
import com.example.hms.storage.FileStorage;
import com.example.hms.storage.StorageKeys;
import com.example.hms.storage.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Patient photos (P3 #21), kept in {@link FileStorage} under
 * {@code patient-photos/<name>}: the directory they always had under the
 * upload root (QA D1184), so the names stored on patient rows resolve in the
 * local directory and in the bucket alike. A patient photo leaves the server
 * only through the authenticated streaming endpoint: the only stored class
 * served without a token is profile images ({@code ProfileImageController},
 * SecurityConfig). The patient row stores the bare file name.
 * Validation mirrors the profile-image rules (5 MB, image/*, extension allowlist).
 */
@Slf4j
@Service
@Transactional
public class PatientPhotoServiceImpl implements PatientPhotoService {

    private static final long MAX_PHOTO_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS =
        Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    /** The storage prefix of patient photos: the subdirectory they had under the upload root. */
    static final String PHOTO_PREFIX = "patient-photos/";

    private final PatientRepository patientRepository;
    private final FileStorage storage;

    public PatientPhotoServiceImpl(PatientRepository patientRepository, FileStorage storage) {
        this.patientRepository = patientRepository;
        this.storage = storage;
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
        validate(file);

        String extension = extensionOf(file.getOriginalFilename());
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
                storage.put(key, in, file.getSize(), file.getContentType());
            }

            String previousPath = patient.getPhotoFilePath();
            LocalDateTime now = LocalDateTime.now();
            patient.setPhotoFilePath(filename);
            patient.setPhotoContentType(file.getContentType());
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
        try {
            Optional<StoredObject> stored = storage.get(key);
            if (stored.isEmpty()) {
                // QA D1184 (round 22): recorded but no longer stored.
                log.warn("Patient photo file missing in {}", storage.describe());
                throw new ResourceNotFoundException("patientPhoto.notFound", patientId);
            }
            try (StoredObject object = stored.get()) {
                return new PhotoPayload(object.content().readAllBytes(),
                    patient.getPhotoContentType() != null ? patient.getPhotoContentType() : "image/jpeg");
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

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("file", "patient.photo.required");
        }
        if (file.getSize() > MAX_PHOTO_SIZE) {
            throw new BusinessException("patient.photo.tooLarge");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException("patient.photo.imageOnly");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("patient.photo.typeUnsupported");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
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
