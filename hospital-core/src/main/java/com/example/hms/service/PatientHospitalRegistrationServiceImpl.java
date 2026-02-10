package com.example.hms.service;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.exception.PatientAlreadyRegisteredException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientHospitalRegistrationMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.PatientMultiHospitalSummaryDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientHospitalRegistrationServiceImpl implements PatientHospitalRegistrationService {

    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationMapper mapper;

    private static final String MRN_PREFIX = "mrn-";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MRN_CODE_LENGTH = 7;
    private static final int MAX_MRN_GENERATION_ATTEMPTS = 10;
    private static final String MSG_REG_NOT_FOUND_ID = "Registration not found with ID: ";
    private static final String MSG_REG_NOT_FOUND_MRN = "Registration not found with mrn: ";

    // -------------------- CREATE --------------------
    @Override
    @Transactional
    public PatientHospitalRegistrationResponseDTO registerPatient(PatientHospitalRegistrationRequestDTO dto) {
        validateRequest(dto);

        final Patient patient;
        if (!isBlank(dto.getPatientUsername())) {
            String identifier = dto.getPatientUsername().trim();
            patient = patientRepository.findByUsernameOrEmail(identifier)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with username/email: " + identifier));
        } else {
            patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + dto.getPatientId()));
        }

        final Hospital hospital = !isBlank(dto.getHospitalName())
            ? hospitalRepository.findByName(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with name: " + dto.getHospitalName()))
            : hospitalRepository.findById(dto.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + dto.getHospitalId()));

        log.info("Hospital retrieved: name={}", hospital.getName());

        // Prevent duplicate registration for same hospital
        if (registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospital.getId())) {
            throw new PatientAlreadyRegisteredException(
                String.format("Patient '%s' is already registered to Hospital '%s'.", patient.getId(), hospital.getId())
            );
        }

        // Minor guard
        if (patient.getDateOfBirth() != null
            && Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears() < 18
            && (isBlank(patient.getEmergencyContactName())
                || isBlank(patient.getEmergencyContactPhone())
                || isBlank(patient.getEmergencyContactRelationship()))) {
            throw new IllegalArgumentException("Minor patients must have a guardian's contact information.");
        }

        // Generate mrn server-side (immutable)
        String mrn = generateUniquemrn(hospital.getId());

        PatientHospitalRegistration registration = mapper.toEntity(dto, patient, hospital);
        registration.setMrn(mrn);
        registration.setActive(true);
        registration.setRegistrationDate(LocalDate.now());

        PatientHospitalRegistration saved = registrationRepository.save(registration);

        log.info("✅ Registered Patient '{}' to Hospital '{}' with mrn '{}'", patient.getId(), hospital.getId(), mrn);

        return mapper.toResponseDTO(saved);
    }

    // -------------------- READ --------------------
    @Override
    @Transactional(readOnly = true)
    public PatientHospitalRegistrationResponseDTO getById(UUID id) {
        PatientHospitalRegistration reg = registrationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_ID + id));
        return mapper.toResponseDTO(reg);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(String patientUsername) {
        log.debug("Fetching registrations for patient username: {}", patientUsername);
        return registrationRepository.findByPatientUsername(patientUsername)
            .stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(String patientUsername, int page, int size, Boolean active) {
        log.debug("Fetching registrations for patient username: {}, page: {}, size: {}, active: {}", patientUsername, page, size, active);

        List<PatientHospitalRegistration> registrations = registrationRepository.findByPatientUsername(patientUsername);
        if (active != null) {
            registrations = registrations.stream()
                .filter(r -> r.isActive() == active)
                .toList();
        }
        int fromIndex = Math.min(page * size, registrations.size());
        int toIndex = Math.min(fromIndex + size, registrations.size());
        List<PatientHospitalRegistration> paged = registrations.subList(fromIndex, toIndex);

        return paged.stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    // UUID-based overloads — PRIMARY (match your controller)
    @Override
    @Transactional(readOnly = true)
    public List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(UUID patientId, int page, int size, Boolean active) {
        log.debug("Fetch by patientId={} page={} size={} active={}", patientId, page, size, active);
        List<PatientHospitalRegistration> registrations = registrationRepository.findByPatientId(patientId);
        if (active != null) {
            registrations = registrations.stream()
                .filter(r -> r.isActive() == active)
                .toList();
        }
        int fromIndex = Math.min(page * size, registrations.size());
        int toIndex = Math.min(fromIndex + size, registrations.size());
        return registrations.subList(fromIndex, toIndex).stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientHospitalRegistrationResponseDTO> getRegistrationsByPatient(UUID patientId) {
        log.debug("Fetch by patientId={}", patientId);
        return registrationRepository.findByPatientId(patientId).stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientHospitalRegistrationResponseDTO> getRegistrationsByHospital(
        UUID hospitalId, int page, int size, Boolean active
    ) {
        log.debug("Fetch by hospitalId={} page={} size={} active={}", hospitalId, page, size, active);
        List<PatientHospitalRegistration> registrations = registrationRepository.findByHospitalId(hospitalId);
        if (active != null) {
            registrations = registrations.stream()
                .filter(r -> r.isActive() == active)
                .toList();
        }
        int fromIndex = Math.min(page * size, registrations.size());
        int toIndex = Math.min(fromIndex + size, registrations.size());
        return registrations.subList(fromIndex, toIndex).stream()
            .map(mapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientMultiHospitalSummaryDTO> getPatientsRegisteredInMultipleHospitals() {
        return registrationRepository.findPatientsRegisteredInMultipleHospitals();
    }

    // -------------------- UPDATE --------------------
    // MRI-based (kept)
    @Override
    @Transactional
    public PatientHospitalRegistrationResponseDTO updateRegistration(String mrn, PatientHospitalRegistrationRequestDTO dto) {
        log.debug("Updating registration mrn: {}", mrn);

        PatientHospitalRegistration registration = registrationRepository.findByMrnAndHospitalName(mrn, dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_MRN + mrn));

        applyEditableFields(registration, dto, false);
        PatientHospitalRegistration updated = registrationRepository.save(registration);

        log.info("✅ Updated registration mrn: {}", mrn);
        return mapper.toResponseDTO(updated);
    }

    @Override
    public PatientHospitalRegistrationResponseDTO patchRegistration(String mrn, PatientHospitalRegistrationRequestDTO dto) {
        log.debug("Patching registration mrn: {}", mrn);

        PatientHospitalRegistration registration = registrationRepository.findByMrnAndHospitalName(mrn, dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_MRN + mrn));

        applyEditableFields(registration, dto, true);
        PatientHospitalRegistration updated = registrationRepository.save(registration);
        log.info("✅ Patched registration mrn: {}", mrn);
        return mapper.toResponseDTO(updated);
    }

    // UUID-based (primary for your controller)
    @Override
    @Transactional
    public void deregisterPatient(String mrn) {
        log.debug("Deregistering patient registration mrn: {}", mrn);

        PatientHospitalRegistration registration = registrationRepository.findByMrn(mrn)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_MRN + mrn));

        registrationRepository.delete(registration);
        log.info("🗑️ Deregistered Patient Registration mrn '{}'", mrn);
    }

    // UUID-based (primary for your controller)
    @Override
    @Transactional
    public void deregisterPatient(UUID id) {
        log.debug("Deregister by registration UUID={}", id);
        PatientHospitalRegistration registration = registrationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_ID + id));
        registrationRepository.delete(registration);
        log.info("🗑️ Deregistered Patient Registration ID '{}'", id);
    }

    // UUID-based (primary for your controller)
    @Override
    @Transactional
    public PatientHospitalRegistrationResponseDTO updateRegistration(UUID id, PatientHospitalRegistrationRequestDTO dto) {
        log.debug("Update by registration UUID={}", id);
        PatientHospitalRegistration registration = registrationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_ID + id));
        applyEditableFields(registration, dto, false);
        return mapper.toResponseDTO(registrationRepository.save(registration));
    }

    @Override
    public PatientHospitalRegistrationResponseDTO patchRegistration(UUID id, PatientHospitalRegistrationRequestDTO dto) {
        log.debug("Patch by registration UUID={}", id);
        PatientHospitalRegistration registration = registrationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_REG_NOT_FOUND_ID + id));
        applyEditableFields(registration, dto, true);
        return mapper.toResponseDTO(registrationRepository.save(registration));
    }

    // -------------------- helpers --------------------
    private void validateRequest(PatientHospitalRegistrationRequestDTO dto) {
        boolean hasUserRef = !isBlank(dto.getPatientUsername()) || dto.getPatientId() != null;
        boolean hasHospitalRef = !isBlank(dto.getHospitalName()) || dto.getHospitalId() != null;
        if (!hasUserRef) throw new IllegalArgumentException("Patient username or patientId is required.");
        if (!hasHospitalRef) throw new IllegalArgumentException("Hospital name or hospitalId is required.");
    }

    /**
     * Generates a unique mrn for the given hospital. Enforces uniqueness with repository checks.
     */
    private String generateUniquemrn(UUID hospitalId) {
        for (int attempt = 1; attempt <= MAX_MRN_GENERATION_ATTEMPTS; attempt++) {
            String generatedmrn = MRN_PREFIX + RANDOM.ints(MRN_CODE_LENGTH, 0, ALPHANUMERIC.length())
                .mapToObj(ALPHANUMERIC::charAt)
                .map(Object::toString)
                .collect(Collectors.joining());

            if (!registrationRepository.existsByMrnAndHospitalId(generatedmrn, hospitalId)) {
                log.debug("Generated unique mrn '{}' (attempt {})", generatedmrn, attempt);
                return generatedmrn;
            }
        }
    throw new IllegalStateException("Failed to generate a unique mrn after " + MAX_MRN_GENERATION_ATTEMPTS + " attempts.");
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void applyEditableFields(PatientHospitalRegistration registration, PatientHospitalRegistrationRequestDTO dto, boolean isPatch) {
        if (dto == null) {
            return;
        }

        if (!isPatch || dto.isActive() != registration.isActive()) {
            registration.setActive(dto.isActive());
        }

        if (!isPatch || dto.getCurrentRoom() != null) {
            registration.setCurrentRoom(trim(dto.getCurrentRoom()));
        }

        if (!isPatch || dto.getCurrentBed() != null) {
            registration.setCurrentBed(trim(dto.getCurrentBed()));
        }

        if (!isPatch || dto.getAttendingPhysicianName() != null) {
            registration.setAttendingPhysicianName(trim(dto.getAttendingPhysicianName()));
        }

        PatientStayStatus status = dto.getStayStatus();
        if (!isPatch || status != null) {
            registration.setStayStatus(status);
        }

        if (!isPatch || dto.getReadyForDischargeNote() != null) {
            registration.setReadyForDischargeNote(trim(dto.getReadyForDischargeNote()));
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
