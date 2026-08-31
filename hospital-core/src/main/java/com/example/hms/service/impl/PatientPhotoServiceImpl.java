package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientPhotoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Filesystem-backed patient photos (P3 #21). The storage directory is a
 * SIBLING of the public upload dir, never inside it — {@code /uploads/**}
 * is served permitAll and a patient photo must only leave the server
 * through the authenticated streaming endpoint. Validation mirrors the
 * profile-image rules (5 MB, image/*, extension allowlist).
 */
@Slf4j
@Service
@Transactional
public class PatientPhotoServiceImpl implements PatientPhotoService {

    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found with ID: ";
    private static final long MAX_PHOTO_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS =
        Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    private final PatientRepository patientRepository;
    private final Path photoDir;

    public PatientPhotoServiceImpl(PatientRepository patientRepository,
                                   @Value("${app.patient-photo.dir:patient-photos}") String photoDir) {
        this.patientRepository = patientRepository;
        this.photoDir = Paths.get(photoDir).toAbsolutePath().normalize();
    }

    @Override
    public LocalDateTime upload(UUID patientId, UUID hospitalId, MultipartFile file) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to store a patient photo.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
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
        try {
            Files.createDirectories(photoDir);
            Path target = photoDir.resolve(filename).normalize();
            if (!target.startsWith(photoDir)) {
                throw new BusinessException("Invalid file name.");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

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
            throw new ResourceNotFoundException("No photo on file for patient " + patientId);
        }
        Path path = photoDir.resolve(storedName).normalize();
        if (!path.startsWith(photoDir) || !Files.exists(path)) {
            throw new ResourceNotFoundException("No photo on file for patient " + patientId);
        }
        try {
            return new PhotoPayload(Files.readAllBytes(path),
                patient.getPhotoContentType() != null ? patient.getPhotoContentType() : "image/jpeg");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the patient photo", e);
        }
    }

    @Override
    public void delete(UUID patientId, UUID hospitalId) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to remove a patient photo.");
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
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        // 404-not-403: an unregistered patient looks exactly like a missing one.
        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId);
        }
        return patient;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("A photo file is required.");
        }
        if (file.getSize() > MAX_PHOTO_SIZE) {
            throw new BusinessException("The photo exceeds the 5 MB limit.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException("Only image files are accepted.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("Unsupported image type. Allowed: jpg, jpeg, png, gif, webp.");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private void deleteQuietly(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return;
        }
        try {
            Path path = photoDir.resolve(storedName).normalize();
            if (path.startsWith(photoDir)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Failed to delete previous patient photo {}: {}", storedName, e.getMessage());
        }
    }
}
