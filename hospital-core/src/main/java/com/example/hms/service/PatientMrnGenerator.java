package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * The one MRN generator for every path that opens a patient registration at a
 * hospital (QA D763): the hospital prefix (its code, else its name, three
 * letters) followed by four digits, unique per hospital. The patient-creation
 * path and the "link to this hospital" path used two generators, and the link
 * path produced "mrn-" plus random characters.
 */
@Component
@RequiredArgsConstructor
public class PatientMrnGenerator {

    static final String DEFAULT_PREFIX = "MRX";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 10;

    private final PatientHospitalRegistrationRepository registrationRepository;

    public String generate(Hospital hospital) {
        if (hospital == null || hospital.getId() == null) {
            throw new IllegalArgumentException("patient.r7.mrnNeedsHospital");
        }
        String prefix = prefixFor(hospital);
        UUID hospitalId = hospital.getId();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = prefix + String.format("%04d", SECURE_RANDOM.nextInt(0, 10_000));
            if (!registrationRepository.existsByMrnAndHospitalId(candidate, hospitalId)) {
                return candidate;
            }
        }
        throw new BusinessException("patient.r7.mrnExhausted");
    }

    static String prefixFor(Hospital hospital) {
        String prefix = sanitizePrefix(hospital.getCode());
        if (prefix != null) {
            return prefix;
        }
        prefix = sanitizePrefix(hospital.getName());
        return prefix != null ? prefix : DEFAULT_PREFIX;
    }

    private static String sanitizePrefix(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.length() >= 3) {
            return cleaned.substring(0, 3);
        }
        return (cleaned + DEFAULT_PREFIX).substring(0, 3);
    }
}
