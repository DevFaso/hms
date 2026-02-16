package com.example.hms.service;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.PharmacyLocationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PharmacyDirectoryServiceImpl implements PharmacyDirectoryService {

    private static final String MAIL_ORDER_NAME = "Direct Mail Order Pharmacy";
    private static final String MAIL_ORDER_PHONE = "+1-800-555-0100";

    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PharmacyLocationResponseDTO> listPatientPharmacies(UUID patientId, UUID hospitalId) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required to load pharmacy options.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context is required to load pharmacy options.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));

        if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)) {
            throw new BusinessException("Patient is not registered in the requested hospital context.");
        }

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseGet(patient::getPrimaryHospital);

        Map<UUID, PharmacyLocationResponseDTO> options = new LinkedHashMap<>();
        addPreferredPharmacy(options, patient);
        addHospitalDispensary(options, hospital);
        addMailOrderOption(options, patient);

        return List.copyOf(options.values());
    }

    private void addPreferredPharmacy(Map<UUID, PharmacyLocationResponseDTO> store, Patient patient) {
        String preferred = trimToNull(patient.getPreferredPharmacy());
        if (preferred == null) {
            return;
        }
        UUID id = stableId("preferred:" + patient.getId());
        store.put(id, PharmacyLocationResponseDTO.builder()
            .id(id)
            .name(preferred)
            .mode(PharmacyFulfillmentMode.COMMUNITY)
            .preferred(Boolean.TRUE)
            .supportsEprescribe(Boolean.TRUE)
            .supportsControlledSubstances(Boolean.FALSE)
            .build());
    }

    private void addHospitalDispensary(Map<UUID, PharmacyLocationResponseDTO> store, Hospital hospital) {
        if (hospital == null || hospital.getId() == null) {
            return;
        }
        UUID id = stableId("inpatient:" + hospital.getId());
        String name = Objects.requireNonNullElse(hospital.getName(), "Hospital Pharmacy") + " Inpatient Dispensary";
        store.put(id, PharmacyLocationResponseDTO.builder()
            .id(id)
            .name(name.trim())
            .mode(PharmacyFulfillmentMode.INPATIENT_DISPENSARY)
            .addressLine1(trimToNull(hospital.getAddress()))
            .city(trimToNull(hospital.getCity()))
            .state(trimToNull(hospital.getState()))
            .postalCode(trimToNull(hospital.getZipCode()))
            .phoneNumber(trimToNull(hospital.getPhoneNumber()))
            .supportsEprescribe(Boolean.FALSE)
            .supportsControlledSubstances(Boolean.TRUE)
            .build());
    }

    private void addMailOrderOption(Map<UUID, PharmacyLocationResponseDTO> store, Patient patient) {
        UUID id = stableId("mail:" + patient.getId());
        store.put(id, PharmacyLocationResponseDTO.builder()
            .id(id)
            .name(MAIL_ORDER_NAME)
            .mode(PharmacyFulfillmentMode.MAIL_ORDER)
            .phoneNumber(MAIL_ORDER_PHONE)
            .supportsEprescribe(Boolean.TRUE)
            .supportsControlledSubstances(Boolean.FALSE)
            .build());
    }

    private UUID stableId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
