package com.example.hms.service;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ProblemChangeType;
import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdvanceDirectiveMapper;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.mapper.NursingNoteMapper;
import com.example.hms.mapper.PatientAllergyMapper;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.mapper.PatientProblemMapper;
import com.example.hms.mapper.PatientSurgicalHistoryMapper;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientProblemHistory;
import com.example.hms.model.PatientSurgicalHistory;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.NursingNote;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.DoctorPatientRecordDTO;
import com.example.hms.payload.dto.DoctorPatientRecordRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.PatientAllergyRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisUpdateRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientSearchCriteria;
import com.example.hms.payload.dto.PatientTimelineAccessRequestDTO;
import com.example.hms.payload.dto.PatientTimelineEntryDTO;
import com.example.hms.payload.dto.PatientTimelineResponseDTO;
import com.example.hms.payload.dto.PatientAllergyResponseDTO;
import com.example.hms.payload.dto.PatientProblemResponseDTO;
import com.example.hms.payload.dto.PatientSurgicalHistoryResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemHistoryRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientSurgicalHistoryRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.DiagnosisCodeValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientServiceImpl implements PatientService {

    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";
    private static final String MSG_USER_NOT_FOUND_PREFIX = "User not found with ID: ";
    private static final String MSG_HOSPITAL_NOT_FOUND = "Hospital not found with ID: ";
    private static final String MSG_ALLERGY_NOT_FOUND = "Allergy entry not found for the specified context.";
    private static final String DEFAULT_UNKNOWN = "Unknown";
    private static final String DEFAULT_PREFIX = "MRX";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int DEFAULT_TIMELINE_LIMIT = 50;
    private static final int MAX_TIMELINE_LIMIT = 200;
    private static final String CATEGORY_ENCOUNTER = "ENCOUNTER";
    private static final String CATEGORY_PRESCRIPTION = "PRESCRIPTION";
    private static final String CATEGORY_LAB_RESULT = "LAB_RESULT";
    private static final String CATEGORY_ALLERGY = "ALLERGY";
    private static final String CATEGORY_IMAGING = "IMAGING";
    private static final String CATEGORY_PROCEDURE = "PROCEDURE";
    private static final String SECTION_ENCOUNTERS = "ENCOUNTERS";
    private static final String SECTION_ALLERGIES = "ALLERGIES";
    private static final String SECTION_MEDICATIONS = "MEDICATIONS";
    private static final String SECTION_LABS = "LABS";
    private static final String SECTION_IMAGING = "IMAGING";
    private static final String SECTION_NOTES = "NOTES";
    private static final String SECTION_MEDICAL_HISTORY = "MEDICAL_HISTORY";
    private static final int DEFAULT_RECENT_ENCOUNTER_LIMIT = 10;
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
        "mental health",
        "psychiatry",
        "substance",
        "rehab",
        "dependency",
        "opioid",
        "hiv",
        "aids",
        "sexual health",
        "reproductive",
        "fertility",
        "abortion",
        "oncology",
        "chemotherapy",
        "radiation",
        "gender affirming",
        "domestic violence",
        "assault",
        "trauma"
    );
    private static final Set<String> SENSITIVE_DEPARTMENTS = Set.of(
        "behavioral health",
        "mental health",
        "psychiatry",
        "addiction medicine",
        "oncology",
        "infectious disease"
    );
    private static final Set<String> HIGH_ALERT_MEDICATION_KEYWORDS = Set.of(
        "opioid",
        "fentanyl",
        "oxycodone",
        "hydromorphone",
        "buprenorphine",
        "methadone",
        "ketamine",
        "clozapine"
    );
    private static final String META_STATUS = "status";
    private static final String LOG_UNKNOWN = "UNKNOWN";

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final MessageSource messageSource;
    private final UserRepository userRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientInsuranceService patientInsuranceService;
    private final PatientVitalSignService patientVitalSignService;
    private final EncounterRepository encounterRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final LabResultRepository labResultRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AuditEventLogService auditEventLogService;
    private final PatientAllergyMapper patientAllergyMapper;
    private final PrescriptionMapper prescriptionMapper;
    private final LabResultMapper labResultMapper;
    private final PatientProblemRepository patientProblemRepository;
    private final PatientProblemHistoryRepository patientProblemHistoryRepository;
    private final PatientProblemMapper patientProblemMapper;
    private final PatientSurgicalHistoryRepository patientSurgicalHistoryRepository;
    private final PatientSurgicalHistoryMapper patientSurgicalHistoryMapper;
    private final AdvanceDirectiveRepository advanceDirectiveRepository;
    private final AdvanceDirectiveMapper advanceDirectiveMapper;
    private final UltrasoundOrderRepository ultrasoundOrderRepository;
    private final UltrasoundReportRepository ultrasoundReportRepository;
    private final UltrasoundMapper ultrasoundMapper;
    private final NursingNoteRepository nursingNoteRepository;
    private final NursingNoteMapper nursingNoteMapper;
    private final StaffRepository staffRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<PatientResponseDTO> getAllPatients(UUID hospitalId, Locale locale) {
        List<Patient> patients = (hospitalId != null)
            ? patientRepository.findByHospitalId(hospitalId)
            : patientRepository.findAll();

        return patients.stream()
            .map(patient -> buildPatientDto(patient, hospitalId))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PatientResponseDTO getPatientById(UUID id, UUID hospitalId, Locale locale) {
        Patient patient = patientRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{id}, locale)
            ));

        if (hospitalId != null && !registrationRepository.isPatientRegisteredInHospitalFixed(id, hospitalId)) {
            throw new IllegalStateException("Patient is not registered in the specified hospital.");
        }

        return buildPatientDto(patient, hospitalId);
    }

    @Override
    public PatientResponseDTO createPatient(PatientRequestDTO dto, Locale locale) {
        User user = userRepository.findById(dto.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_USER_NOT_FOUND_PREFIX + dto.getUserId()));

        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + dto.getHospitalId()));

        Patient patient = patientRepository.findByUserId(user.getId())
            .orElseGet(() -> patientRepository.save(patientMapper.toPatient(dto, user)));

        ensurePatientRegistration(patient, hospital);

        if (dto.getInsurance() != null) {
            PatientInsuranceRequestDTO insuranceDTO = dto.getInsurance();
            if (insuranceDTO.getPatientId() == null) {
                insuranceDTO.setPatientId(patient.getId());
            }
            patientInsuranceService.addInsuranceToPatient(insuranceDTO, locale);
        }

        return buildPatientDto(patient, hospital.getId());
    }

    @Override
    @Transactional
    public PatientResponseDTO updatePatient(UUID id, PatientRequestDTO dto, Locale locale) {
        Patient patient = patientRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{id}, locale)
            ));

        User user = userRepository.findById(dto.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_USER_NOT_FOUND_PREFIX + dto.getUserId()));

        patientMapper.updatePatientFromDto(dto, patient, user);
        Patient updatedPatient = patientRepository.save(patient);

        return buildPatientDto(updatedPatient, dto.getHospitalId());
    }

    @Override
    @Transactional
    public PatientResponseDTO patchPatient(UUID id, PatientProfileUpdateRequestDTO request, Locale locale) {
        if (request == null) {
            throw new BusinessException("Update payload is required.");
        }
        Patient patient = patientRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{id}, locale)
            ));

        boolean updated = false;

        updated |= applyNullable(request.getPhoneNumberPrimary(), patient::setPhoneNumberPrimary);
        updated |= applyNullable(request.getPhoneNumberSecondary(), patient::setPhoneNumberSecondary);
        updated |= applyNullable(request.getEmail(), patient::setEmail);

        boolean addressTouched = false;
        addressTouched |= applyNullable(request.getAddressLine1(), patient::setAddressLine1);
        addressTouched |= applyNullable(request.getAddressLine2(), patient::setAddressLine2);
        addressTouched |= applyNullable(request.getCity(), patient::setCity);
        addressTouched |= applyNullable(request.getState(), patient::setState);
        addressTouched |= applyNullable(request.getPostalCode(), patient::setZipCode);
        addressTouched |= applyNullable(request.getCountry(), patient::setCountry);

        if (addressTouched) {
            updated = true;
            patient.setAddress(buildMailingAddress(
                patient.getAddressLine1(),
                patient.getAddressLine2(),
                patient.getCity(),
                patient.getState(),
                patient.getZipCode(),
                patient.getCountry()
            ));
        }

        updated |= applyNullable(request.getEmergencyContactName(), patient::setEmergencyContactName);
        updated |= applyNullable(request.getEmergencyContactPhone(), patient::setEmergencyContactPhone);
        updated |= applyNullable(request.getPreferredPharmacy(), patient::setPreferredPharmacy);
        updated |= applyNullable(request.getCareTeamNotes(), patient::setCareTeamNotes);

        if (request.getChronicConditions() != null) {
            patient.setChronicConditions(joinChronicConditions(request.getChronicConditions()));
            updated = true;
        }

        if (updated) {
            patientRepository.save(patient);
        }

        return buildPatientDto(patient, patient.getHospitalId());
    }

    @Override
    @Transactional
    public void deletePatient(UUID id, Locale locale) {
        if (!patientRepository.existsById(id)) {
            throw new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{id}, locale)
            );
        }
        patientRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientResponseDTO> searchPatients(PatientSearchCriteria criteria, int page, int size, Locale locale) {
        Pageable pageable = PageRequest.of(page, size);
        String mrn = trimToNull(criteria.getMrn());
        String namePattern = buildLowercaseContainsPattern(criteria.getName());
        String dob = trimToNull(criteria.getDateOfBirth());
        String phonePattern = buildPhoneSearchPattern(criteria.getPhone());
        String emailPattern = buildLowercaseContainsPattern(criteria.getEmail());
        UUID hospitalId = criteria.getHospitalId();
        Boolean activeFilter = criteria.getActive() == null ? Boolean.TRUE : criteria.getActive();

        Page<Patient> patientPage = patientRepository.searchPatientsExtended(
            mrn,
            namePattern,
            dob,
            phonePattern,
            emailPattern,
            hospitalId,
            activeFilter,
            pageable
        );
        return patientPage.stream()
            .map(patient -> buildPatientDto(patient, hospitalId))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRegisteredInHospital(UUID patientId, UUID hospitalId) {
        return registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getMrnForHospital(UUID patientId, UUID hospitalId) {
        return patientRepository.findMrnForHospital(patientId, hospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientResponseDTO> getPatientsByHospital(UUID hospitalId, Locale locale) {
        List<Patient> patients = patientRepository.findByHospitalId(hospitalId);
        return patients.stream()
            .map(patient -> buildPatientDto(patient, hospitalId))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PatientResponseDTO> getPatientPageByHospital(UUID hospitalId, Boolean activeOnly, Pageable pageable) {
        Page<Patient> patientPage = patientRepository.findPageByHospitalIdAndActive(hospitalId, activeOnly, pageable);
        return patientPage.map(patient -> buildPatientDto(patient, hospitalId));
    }

    /**
     * Backfills missing Patient entities for users with the "patient" role who do not have a Patient record.
     * This method ensures that every user with the patient role has a corresponding Patient entry,
     * using default values for missing information.
     * Useful for data consistency and migration scenarios.
     */
    @Override
    @Transactional
    public void backfillMissingPatients() {
        List<User> users = userRepository.findUsersWithRolePatientButNoPatientEntry();
        for (User user : users) {
            PatientRequestDTO dto = PatientRequestDTO.builder()
                .userId(user.getId())
                .firstName(Optional.ofNullable(user.getFirstName()).orElse(DEFAULT_UNKNOWN))
                .lastName(Optional.ofNullable(user.getLastName()).orElse(DEFAULT_UNKNOWN))
                .email(user.getEmail())
                .phoneNumberPrimary(Optional.ofNullable(user.getPhoneNumber()).orElse("0000000000"))
                .middleName("-")
                .gender(LOG_UNKNOWN)
                .dateOfBirth(LocalDate.of(1900, 1, 1))
                .address("N/A")
                .emergencyContactName("N/A")
                .emergencyContactPhone("0000000000")
                .emergencyContactRelationship(DEFAULT_UNKNOWN)
                .isActive(true)
                .build();
            createPatient(dto, Locale.ENGLISH);
        }
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('RECEPTIONIST','HOSPITAL_ADMIN')")
    public PatientResponseDTO createPatientByStaff(PatientRequestDTO dto, Locale locale) {
        UUID hospitalId = dto.getHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("Hospital must be resolved from context for staff-created patients.");
        }
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + hospitalId));

        User user = userRepository.findById(dto.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException(MSG_USER_NOT_FOUND_PREFIX + dto.getUserId()));

        Patient patient = patientRepository.findByUserId(user.getId())
            .orElseGet(() -> patientRepository.save(patientMapper.toPatient(dto, user)));

        ensurePatientRegistration(patient, hospital);

        if (dto.getInsurance() != null) {
            PatientInsuranceRequestDTO insuranceDTO = dto.getInsurance();
            if (insuranceDTO.getPatientId() == null) {
                insuranceDTO.setPatientId(patient.getId());
            }
            patientInsuranceService.addInsuranceToPatient(insuranceDTO, locale);
        }

        return buildPatientDto(patient, hospital.getId());
    }

    private void ensurePatientRegistration(Patient patient, Hospital hospital) {
        PatientHospitalRegistration registration = registrationRepository
            .findByPatientIdAndHospitalIdAndActiveTrue(patient.getId(), hospital.getId())
            .orElseGet(() -> {
                String generatedMrn = generateMrn(hospital);
                PatientHospitalRegistration reg = new PatientHospitalRegistration();
                reg.setPatient(patient);
                reg.setHospital(hospital);
                reg.setMrn(generatedMrn);
                reg.setActive(true);
                reg.setRegistrationDate(LocalDate.now());
                return registrationRepository.save(reg);
            });

        if (patient.getHospitalRegistrations() != null && !patient.getHospitalRegistrations().contains(registration)) {
            patient.getHospitalRegistrations().add(registration);
        }
        if (hospital.getPatientRegistrations() != null && !hospital.getPatientRegistrations().contains(registration)) {
            hospital.getPatientRegistrations().add(registration);
        }

        if (patient.getHospitalId() == null) {
            patient.setHospitalId(hospital.getId());
        }
        if (patient.getOrganizationId() == null && hospital.getOrganization() != null) {
            patient.setOrganizationId(hospital.getOrganization().getId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("java:S3776")
    public List<PatientResponseDTO> lookupPatients(String identifier, String email, String phone, String username, String mrn,
                                                   UUID hospitalId, Locale locale) {
        final String normEmail = email != null ? email.trim().toLowerCase() : null;
        final String normIdentifier = identifier != null ? identifier.trim() : null;
        final String normPhone = phone != null ? phone.trim() : null;
        final String normUsername = username != null ? username.trim() : null;
        final String normMrn = mrn != null ? mrn.trim() : null;

        List<Patient> results;
        if (normEmail != null && !normEmail.isBlank()) {
            results = patientRepository.findByEmailContainingIgnoreCase(normEmail);
        } else if (normUsername != null && !normUsername.isBlank()) {
            results = patientRepository.findByUserUsername(normUsername).map(List::of).orElse(List.of());
        } else if (normPhone != null && !normPhone.isBlank()) {
            results = patientRepository.findByPhoneNumberPrimary(normPhone)
                .map(List::of)
                .or(() -> patientRepository.findByPhoneNumberSecondary(normPhone).map(List::of))
                .orElse(List.of());
        } else if (normMrn != null && !normMrn.isBlank()) {
            results = patientRepository.findByMrn(normMrn);
        } else if (normIdentifier != null && !normIdentifier.isBlank()) {
            Optional<Patient> phoneHit = patientRepository.findByPhoneNumberPrimary(normIdentifier)
                .or(() -> patientRepository.findByPhoneNumberSecondary(normIdentifier));
            if (phoneHit.isPresent()) {
                results = List.of(phoneHit.get());
            } else {
                Optional<Patient> emailResult = patientRepository.findByEmailContainingIgnoreCase(normIdentifier).stream().findFirst();
                if (emailResult.isPresent()) {
                    results = List.of(emailResult.get());
                } else {
                    results = patientRepository.findByUserUsername(normIdentifier).map(List::of).orElse(List.of());
                }

                if (results.isEmpty()) {
                    results = patientRepository.findByMrn(normIdentifier);
                }
            }
        } else {
            results = List.of();
        }

        return results.stream()
            .map(p -> buildPatientDto(p, hospitalId))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("java:S3776")
    public PatientTimelineResponseDTO getDoctorTimeline(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        PatientTimelineAccessRequestDTO request
    ) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required for timeline access.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context is required for timeline access.");
        }
        if (assignment == null || assignment.getId() == null) {
            throw new BusinessException("Valid hospital assignment is required for auditing.");
        }
        if (assignment.getHospital() == null || !Objects.equals(assignment.getHospital().getId(), hospitalId)) {
            throw new BusinessException("Assignment hospital does not match the requested context.");
        }
        if (request == null) {
            throw new BusinessException("Timeline request payload is required.");
        }

        String reason = normalizeAccessReason(request.getAccessReason());
        if (reason == null) {
            throw new BusinessException("Access reason must be provided.");
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{patientId}, Locale.getDefault())
            ));

        if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)) {
            throw new BusinessException("Patient is not registered in the requested hospital.");
        }

        boolean includeSensitive = Boolean.TRUE.equals(request.getIncludeSensitiveData());
        int limit = resolveTimelineLimit(request.getMaxEvents());
        Set<String> categoryFilters = normalizeCategoryFilters(request.getCategories());

        List<PatientTimelineEntryDTO> aggregatedEntries = new ArrayList<>();
        aggregatedEntries.addAll(collectEncounterEntries(patientId, hospitalId, categoryFilters));
        aggregatedEntries.addAll(collectPrescriptionEntries(patientId, hospitalId, categoryFilters));
        aggregatedEntries.addAll(collectLabResultEntries(patientId, hospitalId, categoryFilters));
        aggregatedEntries.addAll(collectAllergyEntries(patientId, hospitalId, categoryFilters));
    aggregatedEntries.addAll(collectImagingEntries(patientId, hospitalId, categoryFilters));
    aggregatedEntries.addAll(collectProcedureEntries(patientId, hospitalId, categoryFilters));

        List<PatientTimelineEntryDTO> entries = aggregatedEntries.stream()
            .filter(entry -> includeSensitive || !entry.isSensitive())
            .sorted(Comparator.comparing(
                PatientTimelineEntryDTO::getOccurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())
            ))
            .limit(limit)
            .toList();

        List<String> sensitiveCategories = entries.stream()
            .filter(PatientTimelineEntryDTO::isSensitive)
            .map(PatientTimelineEntryDTO::getCategory)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        PatientTimelineResponseDTO response = PatientTimelineResponseDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .patientName(resolvePatientName(patient))
            .dateOfBirth(patient.getDateOfBirth())
            .accessReason(reason)
            .entries(entries)
            .sensitiveCategories(sensitiveCategories)
            .containsSensitiveData(!sensitiveCategories.isEmpty())
            .totalEntries(entries.size())
            .generatedAt(LocalDateTime.now())
            .build();

        logTimelineAudit(patient, requesterUserId, assignment, reason, includeSensitive, response.getTotalEntries());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("java:S3776")
    public DoctorPatientRecordDTO getDoctorRecord(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        DoctorPatientRecordRequestDTO request
    ) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required for doctor record access.");
        }
        if (request == null) {
            throw new BusinessException("Doctor record request payload is required.");
        }

        UUID resolvedHospitalId = hospitalId != null ? hospitalId : request.getHospitalId();
        if (resolvedHospitalId == null) {
            throw new BusinessException("Hospital context is required for doctor record access.");
        }
        if (request.getHospitalId() != null && !Objects.equals(request.getHospitalId(), resolvedHospitalId)) {
            throw new BusinessException("Hospital mismatch between request payload and context.");
        }
        if (assignment == null || assignment.getId() == null) {
            throw new BusinessException("Valid hospital assignment is required for auditing.");
        }
        if (assignment.getHospital() == null || !Objects.equals(assignment.getHospital().getId(), resolvedHospitalId)) {
            throw new BusinessException("Assignment hospital does not match the requested context.");
        }

        String reason = normalizeAccessReason(request.getAccessReason());
        if (reason == null) {
            throw new BusinessException("Access reason must be provided.");
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{patientId}, Locale.getDefault())
            ));

        if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, resolvedHospitalId)) {
            throw new BusinessException("Patient is not registered in the requested hospital.");
        }

        boolean includeSensitive = Boolean.TRUE.equals(request.getIncludeSensitiveData());
        int maxItems = resolveTimelineLimit(request.getMaxItems());
        int notesLimit = resolveNotesLimit(request.getNotesLimit(), maxItems);

        PatientResponseDTO patientDto = buildPatientDto(patient, resolvedHospitalId);
        String hospitalMrn = getMrnForHospital(patientId, resolvedHospitalId)
            .orElseGet(() -> patient.getMrnForHospital(resolvedHospitalId));

        Set<String> sensitiveSections = new LinkedHashSet<>();

        List<PatientAllergyResponseDTO> allergies = collectDoctorRecordAllergies(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            maxItems,
            sensitiveSections
        );
        List<PrescriptionResponseDTO> medications = collectDoctorRecordMedications(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            maxItems,
            sensitiveSections
        );
        List<LabResultResponseDTO> labResults = collectDoctorRecordLabResults(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            maxItems,
            sensitiveSections
        );
        ImagingBundle imagingBundle = collectDoctorRecordImaging(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            maxItems,
            sensitiveSections
        );
        List<NursingNoteResponseDTO> notes = collectDoctorRecordNursingNotes(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            notesLimit,
            sensitiveSections
        );
        MedicalHistoryBundle medicalHistory = collectDoctorRecordMedicalHistory(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            maxItems
        );
        if (medicalHistory.sensitive()) {
            sensitiveSections.add(SECTION_MEDICAL_HISTORY);
        }

        List<PatientTimelineEntryDTO> recentEncounters = collectRecentEncounterSummaries(
            patientId,
            resolvedHospitalId,
            includeSensitive,
            maxItems
        );
        if (recentEncounters.stream().anyMatch(PatientTimelineEntryDTO::isSensitive)) {
            sensitiveSections.add(SECTION_ENCOUNTERS);
        }

        DoctorPatientRecordDTO response = DoctorPatientRecordDTO.builder()
            .patientId(patientId)
            .hospitalId(resolvedHospitalId)
            .patientName(resolvePatientName(patient))
            .hospitalMrn(hospitalMrn)
            .dateOfBirth(patient.getDateOfBirth())
            .accessReason(reason)
            .generatedAt(LocalDateTime.now())
            .patient(patientDto)
            .allergies(allergies)
            .medications(medications)
            .labResults(labResults)
            .imagingOrders(imagingBundle.orders())
            .imagingReports(imagingBundle.reports())
            .notes(notes)
            .recentEncounters(recentEncounters)
            .problems(medicalHistory.problems())
            .surgicalHistory(medicalHistory.surgicalHistory())
            .advanceDirectives(medicalHistory.advanceDirectives())
            .containsSensitiveData(!sensitiveSections.isEmpty())
            .sensitiveSections(List.copyOf(sensitiveSections))
            .build();

        logDoctorRecordAudit(patient, requesterUserId, assignment, reason, includeSensitive, response, sensitiveSections);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientAllergyResponseDTO> getPatientAllergies(UUID patientId, UUID hospitalId, UUID requesterUserId) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required to load allergies.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context is required to load patient allergies.");
        }
        if (requesterUserId == null) {
            throw new BusinessException("Requester context is required to load patient allergies.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{patientId}, Locale.getDefault())
            ));

        if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)) {
            throw new BusinessException("Patient is not registered in the requested hospital.");
        }

        LinkedHashSet<String> sensitiveSections = new LinkedHashSet<>();
        return collectDoctorRecordAllergies(
            patient.getId(),
            hospitalId,
            true,
            Integer.MAX_VALUE,
            sensitiveSections
        );
    }

    @Override
    @Transactional
    public PatientAllergyResponseDTO createPatientAllergy(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        PatientAllergyRequestDTO request
    ) {
        if (request == null) {
            throw new BusinessException("Allergy payload is required.");
        }
        UUID effectiveHospitalId = request.getHospitalId() != null ? request.getHospitalId() : hospitalId;
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to add allergies.");
        }

        Patient patient = fetchPatient(patientId);
        Hospital hospitalEntity = fetchHospital(effectiveHospitalId);
        ensurePatientRegistered(patient.getId(), hospitalEntity.getId());
        Staff staff = resolveStaffContext(requesterUserId, hospitalEntity.getId());

        PatientAllergy allergy = new PatientAllergy();
        allergy.setPatient(patient);
        allergy.setHospital(hospitalEntity);
        allergy.setRecordedBy(staff);
        allergy.setActive(true);
        patientAllergyMapper.updateEntityFromRequest(request, allergy);
        enforceAllergyDisplay(allergy.getAllergenDisplay());
        if (allergy.getRecordedDate() == null) {
            allergy.setRecordedDate(LocalDate.now());
        }
        if (allergy.getVerificationStatus() == null) {
            allergy.setVerificationStatus(AllergyVerificationStatus.UNCONFIRMED);
        }
        patientAllergyRepository.save(allergy);
        logAllergyMutation("CREATED", patientId, hospitalEntity.getId(), allergy.getId(), requesterUserId, allergy, null);
        return patientAllergyMapper.toResponseDto(allergy);
    }

    @Override
    @Transactional
    public PatientAllergyResponseDTO updatePatientAllergy(
        UUID patientId,
        UUID hospitalId,
        UUID allergyId,
        UUID requesterUserId,
        PatientAllergyRequestDTO request
    ) {
        if (request == null) {
            throw new BusinessException("Allergy payload is required.");
        }
        UUID effectiveHospitalId = request.getHospitalId() != null ? request.getHospitalId() : hospitalId;
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to update allergies.");
        }
        PatientAllergy allergy = loadPatientAllergy(patientId, effectiveHospitalId, allergyId);
        Staff staff = resolveStaffContext(requesterUserId, effectiveHospitalId);
        if (allergy.getRecordedBy() == null) {
            allergy.setRecordedBy(staff);
        }
        patientAllergyMapper.updateEntityFromRequest(request, allergy);
        enforceAllergyDisplay(allergy.getAllergenDisplay());
        if (allergy.getRecordedDate() == null) {
            allergy.setRecordedDate(LocalDate.now());
        }
        patientAllergyRepository.save(allergy);
        logAllergyMutation("UPDATED", patientId, effectiveHospitalId, allergy.getId(), requesterUserId, allergy, null);
        return patientAllergyMapper.toResponseDto(allergy);
    }

    @Override
    @Transactional
    public void deactivatePatientAllergy(
        UUID patientId,
        UUID hospitalId,
        UUID allergyId,
        UUID requesterUserId,
        String reason
    ) {
        UUID effectiveHospitalId = hospitalId;
        if (effectiveHospitalId == null) {
            throw new BusinessException("Hospital context is required to deactivate allergies.");
        }
        String normalizedReason = normalizeChangeReason(reason);
        if (normalizedReason == null) {
            throw new BusinessException("A justification is required to deactivate allergies.");
        }
        PatientAllergy allergy = loadPatientAllergy(patientId, effectiveHospitalId, allergyId);
        resolveStaffContext(requesterUserId, effectiveHospitalId);
        allergy.setActive(false);
        patientAllergyRepository.save(allergy);
        logAllergyMutation("DEACTIVATED", patientId, effectiveHospitalId, allergy.getId(), requesterUserId, allergy, normalizedReason);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientProblemResponseDTO> listPatientDiagnoses(UUID patientId, UUID hospitalId, boolean includeHistorical) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required to list diagnoses.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context is required to list diagnoses.");
        }

        Patient patient = fetchPatient(patientId);
        Hospital hospital = fetchHospital(hospitalId);
        ensurePatientRegistered(patient.getId(), hospital.getId());

        Comparator<PatientProblem> problemComparator = Comparator
            .comparing(PatientProblem::getOnsetDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PatientProblem::getLastReviewedAt, Comparator.nullsLast(Comparator.reverseOrder()));

        return patientProblemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .filter(problem -> includeHistorical || isActiveDiagnosis(problem))
            .sorted(problemComparator)
            .map(patientProblemMapper::toResponseDto)
            .toList();
    }

    @Override
    @Transactional
    public PatientProblemResponseDTO createPatientDiagnosis(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        PatientDiagnosisRequestDTO request
    ) {
        if (requesterUserId == null) {
            throw new BusinessException("Requester context is required to add diagnoses.");
        }
        if (request == null) {
            throw new BusinessException("Diagnosis payload is required.");
        }
        UUID targetHospitalId = request.getHospitalId() != null ? request.getHospitalId() : hospitalId;
        if (targetHospitalId == null) {
            throw new BusinessException("Hospital context is required to add diagnoses.");
        }

        Patient patient = fetchPatient(patientId);
        Hospital hospital = fetchHospital(targetHospitalId);
        ensurePatientRegistered(patient.getId(), hospital.getId());
        Staff staff = resolveStaffContext(requesterUserId, hospital.getId());

        PatientProblem problem = new PatientProblem();
        problem.setPatient(patient);
        problem.setHospital(hospital);
        problem.setRecordedBy(staff);
        updateProblemCoreFields(problem, requireDiagnosisDisplay(request.getProblemDisplay()), request.getProblemCode(), request.getIcdVersion());
        problem.setSeverity(request.getSeverity());
        problem.setOnsetDate(request.getOnsetDate());
        ProblemStatus status = request.getStatus() != null ? request.getStatus() : ProblemStatus.ACTIVE;
        problem.setStatus(status);
        if (status == ProblemStatus.RESOLVED) {
            problem.setResolvedDate(LocalDate.now());
        }
        problem.setSupportingEvidence(trimToNull(request.getSupportingEvidence()));
        problem.setNotes(trimToNull(request.getNotes()));
        problem.setSourceSystem(trimToNull(request.getSourceSystem()));
        problem.setChronic(Boolean.TRUE.equals(request.getChronic()));
        problem.setDiagnosisCodes(new ArrayList<>(DiagnosisCodeValidator.normalizeList(request.getDiagnosisCodes())));
        problem.setLastReviewedAt(LocalDateTime.now());

        patientProblemRepository.save(problem);
        recordProblemHistory(
            problem,
            ProblemChangeType.CREATED,
            null,
            null,
            serializeProblemSnapshot(problem),
            requesterUserId,
            staff
        );
        logDiagnosisMutation(
            "CREATED",
            patientId,
            hospital.getId(),
            problem.getId(),
            requesterUserId,
            problem,
            false
        );
        return patientProblemMapper.toResponseDto(problem);
    }

    @Override
    @Transactional
    public PatientProblemResponseDTO updatePatientDiagnosis(
        UUID patientId,
        UUID hospitalId,
        UUID diagnosisId,
        UUID requesterUserId,
        PatientDiagnosisUpdateRequestDTO request
    ) {
        if (requesterUserId == null) {
            throw new BusinessException("Requester context is required to update diagnoses.");
        }
        if (request == null) {
            throw new BusinessException("Diagnosis update payload is required.");
        }
        UUID targetHospitalId = request.getHospitalId() != null ? request.getHospitalId() : hospitalId;
        if (targetHospitalId == null) {
            throw new BusinessException("Hospital context is required to update diagnoses.");
        }

        PatientProblem problem = loadPatientProblem(patientId, targetHospitalId, diagnosisId);
        Staff staff = resolveStaffContext(requesterUserId, problem.getHospital().getId());
        String beforeSnapshot = serializeProblemSnapshot(problem);
        String normalizedReason = normalizeChangeReason(request.getChangeReason());

        boolean fieldChanges = applyProblemFieldUpdates(problem, request);
        boolean statusChanged = handleDiagnosisStatusChange(problem, request, normalizedReason);
        boolean chronicChanged = handleDiagnosisChronicityChange(problem, request, normalizedReason);

        if (!statusChanged && !chronicChanged && !fieldChanges) {
            return patientProblemMapper.toResponseDto(problem);
        }

        problem.setLastReviewedAt(LocalDateTime.now());
        patientProblemRepository.save(problem);

        ProblemChangeType changeType = resolveProblemChangeType(statusChanged, chronicChanged);

        recordProblemHistory(
            problem,
            changeType,
            normalizedReason,
            beforeSnapshot,
            serializeProblemSnapshot(problem),
            requesterUserId,
            staff
        );
        logDiagnosisMutation(
            changeType.name(),
            patientId,
            problem.getHospital().getId(),
            problem.getId(),
            requesterUserId,
            problem,
            normalizedReason != null
        );
        return patientProblemMapper.toResponseDto(problem);
    }

    @Override
    @Transactional
    public void deletePatientDiagnosis(
        UUID patientId,
        UUID hospitalId,
        UUID diagnosisId,
        UUID requesterUserId,
        String reason
    ) {
        if (requesterUserId == null) {
            throw new BusinessException("Requester context is required to remove diagnoses.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital context is required to remove diagnoses.");
        }
        String normalizedReason = normalizeChangeReason(reason);
        if (normalizedReason == null) {
            throw new BusinessException("A justification is required to remove a diagnosis.");
        }

        PatientProblem problem = loadPatientProblem(patientId, hospitalId, diagnosisId);
        Staff staff = resolveStaffContext(requesterUserId, problem.getHospital().getId());
        String beforeSnapshot = serializeProblemSnapshot(problem);

        problem.setStatus(ProblemStatus.INACTIVE);
        problem.setStatusChangeReason(normalizedReason);
        if (problem.getResolvedDate() == null) {
            problem.setResolvedDate(LocalDate.now());
        }
        problem.setLastReviewedAt(LocalDateTime.now());
        patientProblemRepository.save(problem);

        recordProblemHistory(
            problem,
            ProblemChangeType.DELETED,
            normalizedReason,
            beforeSnapshot,
            serializeProblemSnapshot(problem),
            requesterUserId,
            staff
        );
        logDiagnosisMutation(
            "DELETED",
            patientId,
            hospitalId,
            problem.getId(),
            requesterUserId,
            problem,
            true
        );
    }

    private boolean applyProblemFieldUpdates(PatientProblem problem, PatientDiagnosisUpdateRequestDTO request) {
        boolean updated = false;
        if (request.getProblemDisplay() != null) {
            problem.setProblemDisplay(requireDiagnosisDisplay(request.getProblemDisplay()));
            updated = true;
        }
        if (request.getProblemCode() != null) {
            String targetVersion = request.getIcdVersion() != null
                ? request.getIcdVersion()
                : problem.getIcdVersion();
            problem.setProblemCode(normalizeDiagnosisCode(request.getProblemCode(), targetVersion));
            updated = true;
        }
        if (request.getIcdVersion() != null) {
            problem.setIcdVersion(trimToNull(request.getIcdVersion()));
            updated = true;
        }
        if (request.getSeverity() != null) {
            problem.setSeverity(request.getSeverity());
            updated = true;
        }
        if (request.getOnsetDate() != null) {
            problem.setOnsetDate(request.getOnsetDate());
            updated = true;
        }
        if (request.getDiagnosisCodes() != null) {
            problem.setDiagnosisCodes(new ArrayList<>(DiagnosisCodeValidator.normalizeList(request.getDiagnosisCodes())));
            updated = true;
        }
        if (request.getSupportingEvidence() != null) {
            problem.setSupportingEvidence(trimToNull(request.getSupportingEvidence()));
            updated = true;
        }
        if (request.getNotes() != null) {
            problem.setNotes(trimToNull(request.getNotes()));
            updated = true;
        }
        if (request.getResolvedDate() != null && request.getStatus() == null) {
            problem.setResolvedDate(request.getResolvedDate());
            updated = true;
        }
        return updated;
    }

    private boolean handleDiagnosisStatusChange(
        PatientProblem problem,
        PatientDiagnosisUpdateRequestDTO request,
        String normalizedReason
    ) {
        ProblemStatus requestedStatus = request.getStatus();
        if (requestedStatus == null || requestedStatus == problem.getStatus()) {
            return false;
        }
        if (normalizedReason == null) {
            throw new BusinessException("A reason is required when changing diagnosis status.");
        }
        problem.setStatus(requestedStatus);
        problem.setStatusChangeReason(normalizedReason);
        if (requestedStatus == ProblemStatus.RESOLVED) {
            problem.setResolvedDate(request.getResolvedDate() != null ? request.getResolvedDate() : LocalDate.now());
        } else if (request.getResolvedDate() != null) {
            problem.setResolvedDate(request.getResolvedDate());
        } else if (requestedStatus == ProblemStatus.ACTIVE) {
            problem.setResolvedDate(null);
        }
        return true;
    }

    private boolean handleDiagnosisChronicityChange(
        PatientProblem problem,
        PatientDiagnosisUpdateRequestDTO request,
        String normalizedReason
    ) {
        if (request.getChronic() == null || request.getChronic() == problem.isChronic()) {
            return false;
        }
        if (normalizedReason == null) {
            throw new BusinessException("A reason is required when changing chronic designation.");
        }
        problem.setChronic(request.getChronic());
        problem.setStatusChangeReason(normalizedReason);
        return true;
    }

    private ProblemChangeType resolveProblemChangeType(boolean statusChanged, boolean chronicChanged) {
        if (statusChanged) {
            return ProblemChangeType.STATUS_CHANGED;
        }
        if (chronicChanged) {
            return ProblemChangeType.CHRONICITY_CHANGED;
        }
        return ProblemChangeType.UPDATED;
    }

    private Patient fetchPatient(UUID patientId) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required.");
        }
        return patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_PATIENT_NOT_FOUND, new Object[]{patientId}, Locale.getDefault())
            ));
    }

    private Hospital fetchHospital(UUID hospitalId) {
        if (hospitalId == null) {
            throw new BusinessException("Hospital identifier is required.");
        }
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND + hospitalId));
    }

    private void ensurePatientRegistered(UUID patientId, UUID hospitalId) {
        if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)) {
            throw new BusinessException("Patient is not registered in the requested hospital.");
        }
    }

    private PatientProblem loadPatientProblem(UUID patientId, UUID hospitalId, UUID diagnosisId) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required for diagnosis operations.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital identifier is required for diagnosis operations.");
        }
        if (diagnosisId == null) {
            throw new BusinessException("Diagnosis identifier is required.");
        }
        PatientProblem problem = patientProblemRepository.findById(diagnosisId)
            .orElseThrow(() -> new ResourceNotFoundException("Diagnosis not found with ID: " + diagnosisId));
        if (problem.getPatient() == null || problem.getPatient().getId() == null
            || !problem.getPatient().getId().equals(patientId)
            || problem.getHospital() == null || problem.getHospital().getId() == null
            || !problem.getHospital().getId().equals(hospitalId)) {
            throw new BusinessException("Diagnosis does not belong to the specified patient or hospital.");
        }
        ensurePatientRegistered(patientId, hospitalId);
        return problem;
    }

    private PatientAllergy loadPatientAllergy(UUID patientId, UUID hospitalId, UUID allergyId) {
        if (patientId == null) {
            throw new BusinessException("Patient identifier is required for allergy operations.");
        }
        if (hospitalId == null) {
            throw new BusinessException("Hospital identifier is required for allergy operations.");
        }
        if (allergyId == null) {
            throw new BusinessException("Allergy identifier is required.");
        }
        PatientAllergy allergy = patientAllergyRepository
            .findByIdAndPatient_IdAndHospital_Id(allergyId, patientId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ALLERGY_NOT_FOUND));
        ensurePatientRegistered(patientId, hospitalId);
        return allergy;
    }

    private Staff resolveStaffContext(UUID requesterUserId, UUID hospitalId) {
        if (requesterUserId == null) {
            throw new BusinessException("Requester identifier is required.");
        }
        return staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)
            .orElseThrow(() -> new BusinessException("Requester is not authorized for this hospital."));
    }

    private void updateProblemCoreFields(PatientProblem problem, String display, String code, String icdVersion) {
        problem.setProblemDisplay(display);
        problem.setProblemCode(normalizeDiagnosisCode(code, icdVersion));
        problem.setIcdVersion(trimToNull(icdVersion));
    }

    private String requireDiagnosisDisplay(String display) {
        String normalized = trimToNull(display);
        if (normalized == null) {
            throw new BusinessException("Diagnosis display is required.");
        }
        return normalized;
    }

    private String normalizeDiagnosisCode(String code, String icdVersion) {
        String normalized = DiagnosisCodeValidator.normalize(code);
        if (normalized == null) {
            return null;
        }
        if (requiresIcdValidation(icdVersion) && !DiagnosisCodeValidator.isValidIcd10(normalized)) {
            throw new BusinessException("Diagnosis code must be a valid ICD-10 value.");
        }
        return normalized;
    }

    private boolean requiresIcdValidation(String icdVersion) {
        if (icdVersion == null) {
            return false;
        }
        String normalized = icdVersion.replaceAll("[^A-Za-z0-9]", "")
            .toUpperCase(Locale.ROOT);
        return normalized.startsWith("ICD") || normalized.startsWith("CIM");
    }

    private boolean isActiveDiagnosis(PatientProblem problem) {
        if (problem == null) {
            return false;
        }
        ProblemStatus status = problem.getStatus();
        return status == null || status == ProblemStatus.ACTIVE || status == ProblemStatus.RECURRENCE;
    }

    private String serializeProblemSnapshot(PatientProblem problem) {
        if (problem == null) {
            return null;
        }
        try {
            PatientProblemResponseDTO dto = patientProblemMapper.toResponseDto(problem);
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to serialize patient problem {} snapshot", problem.getId(), ex);
            return null;
        }
    }

    private void logDiagnosisMutation(
        String action,
        UUID patientId,
        UUID hospitalId,
        UUID diagnosisId,
        UUID requesterUserId,
        PatientProblem problem,
        boolean changeReasonProvided
    ) {
        if (!log.isInfoEnabled() || problem == null) {
            return;
        }
        log.info(
            "Diagnosis {} [patientId={}, hospitalId={}, diagnosisId={}, requesterId={}, status={}, icdVersion={}, codeProvided={}, additionalCodes={}, chronic={}, changeReasonProvided={}]",
            action,
            patientId,
            hospitalId,
            diagnosisId,
            requesterUserId,
            problem.getStatus() != null ? problem.getStatus() : LOG_UNKNOWN,
            sanitizeLogValue(problem.getIcdVersion()),
            problem.getProblemCode() != null,
            problem.getDiagnosisCodes() != null ? problem.getDiagnosisCodes().size() : 0,
            problem.isChronic(),
            changeReasonProvided
        );
    }

    private String sanitizeLogValue(String source) {
        if (source == null) {
            return "N/A";
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return "N/A";
        }
        return trimmed.length() > 24 ? trimmed.substring(0, 24) + "…" : trimmed;
    }

    private void recordProblemHistory(
        PatientProblem problem,
        ProblemChangeType changeType,
        String reason,
        String snapshotBefore,
        String snapshotAfter,
        UUID requesterUserId,
        Staff staff
    ) {
        if (problem == null || changeType == null) {
            return;
        }
        PatientProblemHistory history = PatientProblemHistory.builder()
            .problem(problem)
            .patientId(Optional.ofNullable(problem.getPatient()).map(Patient::getId).orElse(null))
            .hospitalId(Optional.ofNullable(problem.getHospital()).map(Hospital::getId).orElse(null))
            .changeType(changeType)
            .reason(normalizeChangeReason(reason))
            .snapshotBefore(snapshotBefore)
            .snapshotAfter(snapshotAfter)
            .changedByUserId(requesterUserId)
            .changedByName(resolveStaffDisplayName(staff))
            .build();
        patientProblemHistoryRepository.save(history);
    }

    private void logAllergyMutation(
        String action,
        UUID patientId,
        UUID hospitalId,
        UUID allergyId,
        UUID requesterUserId,
        PatientAllergy allergy,
        String reason
    ) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info(
            "Allergy {} [patientId={}, hospitalId={}, allergyId={}, requesterId={}, allergen={}, severity={}, verification={}, active={}, reasonProvided={}]",
            action,
            patientId,
            hospitalId,
            allergyId,
            requesterUserId,
            allergy != null ? sanitizeLogValue(allergy.getAllergenDisplay()) : LOG_UNKNOWN,
            allergy != null && allergy.getSeverity() != null ? allergy.getSeverity().name() : LOG_UNKNOWN,
            allergy != null && allergy.getVerificationStatus() != null ? allergy.getVerificationStatus().name() : LOG_UNKNOWN,
            allergy != null && allergy.isActive(),
            reason != null
        );
    }

    private void enforceAllergyDisplay(String display) {
        if (display == null || display.isBlank()) {
            throw new BusinessException("Allergen display name is required.");
        }
    }

    private String normalizeChangeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    private List<PatientTimelineEntryDTO> collectEncounterEntries(UUID patientId, UUID hospitalId, Set<String> categoryFilters) {
        if (!shouldIncludeCategory(categoryFilters, CATEGORY_ENCOUNTER)) {
            return List.of();
        }
        return encounterRepository.findByPatient_Id(patientId).stream()
            .filter(encounter -> encounter.getHospital() != null && hospitalId.equals(encounter.getHospital().getId()))
            .map(encounter -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, META_STATUS, encounter.getStatus() != null ? encounter.getStatus().name() : null);
                putIfNotNull(metadata, "encounterType", encounter.getEncounterType() != null ? encounter.getEncounterType().name() : null);
                putIfNotNull(metadata, "department", encounter.getDepartment() != null ? encounter.getDepartment().getName() : null);
                putIfNotNull(metadata, "clinician", resolveStaffName(encounter));
                return PatientTimelineEntryDTO.builder()
                    .entryId(encounter.getId() != null ? encounter.getId().toString() : null)
                    .category(CATEGORY_ENCOUNTER)
                    .occurredAt(encounter.getEncounterDate())
                    .summary(formatEncounterSummary(encounter))
                    .sensitive(isSensitiveEncounter(encounter))
                    .metadata(metadata)
                    .build();
            })
            .toList();
    }

    private List<PatientTimelineEntryDTO> collectPrescriptionEntries(UUID patientId, UUID hospitalId, Set<String> categoryFilters) {
        if (!shouldIncludeCategory(categoryFilters, CATEGORY_PRESCRIPTION)) {
            return List.of();
        }
        return prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .map(prescription -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, META_STATUS, prescription.getStatus() != null ? prescription.getStatus().name() : null);
                putIfNotNull(metadata, "dosage", prescription.getDosage());
                putIfNotNull(metadata, "frequency", prescription.getFrequency());
                putIfNotNull(metadata, "duration", prescription.getDuration());
                return PatientTimelineEntryDTO.builder()
                    .entryId(prescription.getId() != null ? prescription.getId().toString() : null)
                    .category(CATEGORY_PRESCRIPTION)
                    .occurredAt(coalesce(prescription.getUpdatedAt(), prescription.getCreatedAt()))
                    .summary(formatPrescriptionSummary(prescription))
                    .sensitive(isSensitiveMedication(prescription))
                    .metadata(metadata)
                    .build();
            })
            .toList();
    }

    private List<PatientTimelineEntryDTO> collectLabResultEntries(UUID patientId, UUID hospitalId, Set<String> categoryFilters) {
        if (!shouldIncludeCategory(categoryFilters, CATEGORY_LAB_RESULT)) {
            return List.of();
        }
        return labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
            .filter(result -> result.getLabOrder() != null && result.getLabOrder().getHospital() != null
                && hospitalId.equals(result.getLabOrder().getHospital().getId()))
            .map(result -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, "unit", result.getResultUnit());
                putIfNotNull(metadata, "acknowledged", result.isAcknowledged());
                putIfNotNull(metadata, "released", result.isReleased());
                String summary = formatLabResultSummary(result);
                return PatientTimelineEntryDTO.builder()
                    .entryId(result.getId() != null ? result.getId().toString() : null)
                    .category(CATEGORY_LAB_RESULT)
                    .occurredAt(result.getResultDate())
                    .summary(summary)
                    .sensitive(isSensitiveLabResult(result))
                    .metadata(metadata)
                    .build();
            })
            .toList();
    }

    private List<PatientTimelineEntryDTO> collectAllergyEntries(UUID patientId, UUID hospitalId, Set<String> categoryFilters) {
        if (!shouldIncludeCategory(categoryFilters, CATEGORY_ALLERGY)) {
            return List.of();
        }
        return patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .map(allergy -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, "severity", allergy.getSeverity());
                putIfNotNull(metadata, "reaction", allergy.getReaction());
                putIfNotNull(metadata, "verificationStatus", allergy.getVerificationStatus());
                LocalDateTime occurredAt = toDateTime(Optional.ofNullable(allergy.getLastOccurrenceDate())
                    .orElse(Optional.ofNullable(allergy.getRecordedDate()).orElse(allergy.getOnsetDate())));
                return PatientTimelineEntryDTO.builder()
                    .entryId(allergy.getId() != null ? allergy.getId().toString() : null)
                    .category(CATEGORY_ALLERGY)
                    .occurredAt(occurredAt)
                    .summary(formatAllergySummary(allergy))
                    .sensitive(isSensitiveAllergy(allergy))
                    .metadata(metadata)
                    .build();
            })
            .toList();
    }

    private List<PatientTimelineEntryDTO> collectImagingEntries(UUID patientId, UUID hospitalId, Set<String> categoryFilters) {
        if (!shouldIncludeCategory(categoryFilters, CATEGORY_IMAGING)) {
            return List.of();
        }
        List<UltrasoundOrder> orders = Optional.ofNullable(ultrasoundOrderRepository.findAllByPatientId(patientId))
            .orElse(List.of());
        List<UltrasoundReport> reports = Optional.ofNullable(ultrasoundReportRepository.findAllByPatientId(patientId))
            .orElse(List.of());

        Stream<PatientTimelineEntryDTO> orderEntries = orders.stream()
            .filter(order -> order.getHospital() != null && hospitalId.equals(order.getHospital().getId()))
            .map(order -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, "orderedBy", order.getOrderedBy());
                putIfNotNull(metadata, "priority", order.getPriority());
                putIfNotNull(metadata, "scanType", order.getScanType());
                putIfNotNull(metadata, "scheduledDate", order.getScheduledDate());
                putIfNotNull(metadata, META_STATUS, order.getStatus());
                return PatientTimelineEntryDTO.builder()
                    .entryId(order.getId() != null ? order.getId().toString() : null)
                    .category(CATEGORY_IMAGING)
                    .occurredAt(order.getOrderedDate())
                    .summary(formatImagingOrderSummary(order))
                    .sensitive(isSensitiveUltrasoundOrder(order))
                    .metadata(metadata)
                    .build();
            });

        Stream<PatientTimelineEntryDTO> reportEntries = reports.stream()
            .filter(report -> report.getHospital() != null && hospitalId.equals(report.getHospital().getId()))
            .map(report -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, "scanPerformedBy", report.getScanPerformedBy());
                putIfNotNull(metadata, "reportFinalizedBy", report.getReportFinalizedBy());
                putIfNotNull(metadata, "anomaliesDetected", report.getAnomaliesDetected());
                putIfNotNull(metadata, "followUpRecommendations", truncate(report.getFollowUpRecommendations()));
                return PatientTimelineEntryDTO.builder()
                    .entryId(report.getId() != null ? report.getId().toString() : null)
                    .category(CATEGORY_IMAGING)
                    .occurredAt(toDateTime(report.getScanDate()))
                    .summary(formatImagingReportSummary(report))
                    .sensitive(isSensitiveUltrasoundReport(report))
                    .metadata(metadata)
                    .build();
            });

        return Stream.concat(orderEntries, reportEntries).toList();
    }

    private List<PatientTimelineEntryDTO> collectProcedureEntries(UUID patientId, UUID hospitalId, Set<String> categoryFilters) {
        if (!shouldIncludeCategory(categoryFilters, CATEGORY_PROCEDURE)) {
            return List.of();
        }
        List<PatientSurgicalHistory> procedures = Optional
            .ofNullable(patientSurgicalHistoryRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .orElse(List.of());
        return procedures.stream()
            .map(history -> {
                Map<String, Object> metadata = new HashMap<>();
                putIfNotNull(metadata, "performedBy", resolveStaffDisplayName(history.getPerformedBy()));
                putIfNotNull(metadata, "location", history.getLocation());
                putIfNotNull(metadata, "notes", truncate(history.getNotes()));
                return PatientTimelineEntryDTO.builder()
                    .entryId(history.getId() != null ? history.getId().toString() : null)
                    .category(CATEGORY_PROCEDURE)
                    .occurredAt(toDateTime(history.getProcedureDate()))
                    .summary(formatProcedureSummary(history))
                    .sensitive(isSensitiveSurgicalHistory(history))
                    .metadata(metadata)
                    .build();
            })
            .toList();
    }

    private List<PatientTimelineEntryDTO> collectRecentEncounterSummaries(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int requestedLimit
    ) {
        int safeLimit = requestedLimit > 0 ? Math.min(requestedLimit, DEFAULT_RECENT_ENCOUNTER_LIMIT) : DEFAULT_RECENT_ENCOUNTER_LIMIT;
        List<PatientTimelineEntryDTO> encounterEntries = collectEncounterEntries(
            patientId,
            hospitalId,
            Collections.emptySet()
        );
        return encounterEntries.stream()
            .filter(entry -> includeSensitive || !entry.isSensitive())
            .sorted(Comparator.comparing(
                PatientTimelineEntryDTO::getOccurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())
            ))
            .limit(safeLimit)
            .toList();
    }

    private List<PatientAllergyResponseDTO> collectDoctorRecordAllergies(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int limit,
        Set<String> sensitiveSections
    ) {
        List<PatientAllergy> allergies = patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        Comparator<PatientAllergy> comparator = Comparator
            .comparing((PatientAllergy allergy) -> severityOrder(allergy.getSeverity()))
            .thenComparing(PatientAllergy::getOnsetDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PatientAllergy::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        boolean sectionSensitive = allergies.stream().anyMatch(this::isSensitiveAllergy);
        List<PatientAllergyResponseDTO> responses = allergies.stream()
            .sorted(comparator)
            .filter(allergy -> includeSensitive || !isSensitiveAllergy(allergy))
            .map(patientAllergyMapper::toResponseDto)
            .limit(limit)
            .toList();
        if (sectionSensitive) {
            sensitiveSections.add(SECTION_ALLERGIES);
        }
        return responses;
    }

    private List<PrescriptionResponseDTO> collectDoctorRecordMedications(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int limit,
        Set<String> sensitiveSections
    ) {
        List<Prescription> prescriptions = prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        Comparator<Prescription> comparator = Comparator
            .comparing((Prescription p) -> coalesce(p.getUpdatedAt(), p.getCreatedAt()),
                Comparator.nullsLast(Comparator.reverseOrder()));
        boolean sectionSensitive = prescriptions.stream().anyMatch(this::isSensitiveMedication);
        List<PrescriptionResponseDTO> responses = prescriptions.stream()
            .sorted(comparator)
            .filter(prescription -> includeSensitive || !isSensitiveMedication(prescription))
            .map(prescriptionMapper::toResponseDTO)
            .limit(limit)
            .toList();
        if (sectionSensitive) {
            sensitiveSections.add(SECTION_MEDICATIONS);
        }
        return responses;
    }

    private List<LabResultResponseDTO> collectDoctorRecordLabResults(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int limit,
        Set<String> sensitiveSections
    ) {
        List<LabResult> results = labResultRepository.findByLabOrder_Patient_Id(patientId).stream()
            .filter(result -> result.getLabOrder() != null
                && result.getLabOrder().getHospital() != null
                && hospitalId.equals(result.getLabOrder().getHospital().getId()))
            .sorted(Comparator.comparing(LabResult::getResultDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
        boolean sectionSensitive = results.stream().anyMatch(this::isSensitiveLabResult);
        List<LabResultResponseDTO> responses = results.stream()
            .filter(result -> includeSensitive || !isSensitiveLabResult(result))
            .map(labResultMapper::toResponseDTO)
            .limit(limit)
            .toList();
        if (sectionSensitive) {
            sensitiveSections.add(SECTION_LABS);
        }
        return responses;
    }

    private ImagingBundle collectDoctorRecordImaging(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int limit,
        Set<String> sensitiveSections
    ) {
        List<UltrasoundOrder> orders = ultrasoundOrderRepository.findAllByPatientId(patientId).stream()
            .filter(order -> order.getHospital() != null && hospitalId.equals(order.getHospital().getId()))
            .sorted(Comparator.comparing(UltrasoundOrder::getOrderedDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
        boolean ordersSensitive = orders.stream().anyMatch(this::isSensitiveUltrasoundOrder);
        List<UltrasoundOrderResponseDTO> orderDtos = orders.stream()
            .filter(order -> includeSensitive || !isSensitiveUltrasoundOrder(order))
            .map(ultrasoundMapper::toOrderResponseDTO)
            .limit(limit)
            .toList();

        List<UltrasoundReport> reports = ultrasoundReportRepository.findAllByPatientId(patientId).stream()
            .filter(report -> report.getHospital() != null && hospitalId.equals(report.getHospital().getId()))
            .sorted(Comparator.comparing(UltrasoundReport::getScanDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
        boolean reportsSensitive = reports.stream().anyMatch(this::isSensitiveUltrasoundReport);
        List<UltrasoundReportResponseDTO> reportDtos = reports.stream()
            .filter(report -> includeSensitive || !isSensitiveUltrasoundReport(report))
            .map(ultrasoundMapper::toReportResponseDTO)
            .limit(limit)
            .toList();

        boolean sectionSensitive = ordersSensitive || reportsSensitive;
        if (sectionSensitive) {
            sensitiveSections.add(SECTION_IMAGING);
        }
        return new ImagingBundle(orderDtos, reportDtos, sectionSensitive);
    }

    private List<NursingNoteResponseDTO> collectDoctorRecordNursingNotes(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int limit,
        Set<String> sensitiveSections
    ) {
        List<NursingNote> notes = nursingNoteRepository
            .findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId);
        boolean sectionSensitive = notes.stream().anyMatch(this::isSensitiveNursingNote);
        List<NursingNoteResponseDTO> responses = notes.stream()
            .filter(note -> includeSensitive || !isSensitiveNursingNote(note))
            .map(nursingNoteMapper::toResponse)
            .limit(limit)
            .toList();
        if (sectionSensitive) {
            sensitiveSections.add(SECTION_NOTES);
        }
        return responses;
    }

    private MedicalHistoryBundle collectDoctorRecordMedicalHistory(
        UUID patientId,
        UUID hospitalId,
        boolean includeSensitive,
        int limit
    ) {
        List<PatientProblem> problems = patientProblemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        Comparator<PatientProblem> problemComparator = Comparator
            .comparing(PatientProblem::getOnsetDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PatientProblem::getLastReviewedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        boolean problemSensitive = problems.stream().anyMatch(this::isSensitiveProblem);
        List<PatientProblemResponseDTO> problemDtos = problems.stream()
            .sorted(problemComparator)
            .filter(problem -> includeSensitive || !isSensitiveProblem(problem))
            .map(patientProblemMapper::toResponseDto)
            .limit(limit)
            .toList();

        List<PatientSurgicalHistory> surgicalHistory = patientSurgicalHistoryRepository
            .findByPatient_IdAndHospital_Id(patientId, hospitalId);
        Comparator<PatientSurgicalHistory> surgicalComparator = Comparator
            .comparing(PatientSurgicalHistory::getProcedureDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PatientSurgicalHistory::getLastUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        boolean surgicalSensitive = surgicalHistory.stream().anyMatch(this::isSensitiveSurgicalHistory);
        List<PatientSurgicalHistoryResponseDTO> surgicalDtos = surgicalHistory.stream()
            .sorted(surgicalComparator)
            .filter(history -> includeSensitive || !isSensitiveSurgicalHistory(history))
            .map(patientSurgicalHistoryMapper::toResponseDto)
            .limit(limit)
            .toList();

        List<AdvanceDirective> directives = advanceDirectiveRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        Comparator<AdvanceDirective> directiveComparator = Comparator
            .comparing(AdvanceDirective::getEffectiveDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AdvanceDirective::getLastReviewedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        boolean directiveSensitive = directives.stream().anyMatch(this::isSensitiveAdvanceDirective);
        List<AdvanceDirectiveResponseDTO> directiveDtos = directives.stream()
            .sorted(directiveComparator)
            .filter(directive -> includeSensitive || !isSensitiveAdvanceDirective(directive))
            .map(advanceDirectiveMapper::toResponseDto)
            .limit(limit)
            .toList();

        boolean sectionSensitive = problemSensitive || surgicalSensitive || directiveSensitive;
        return new MedicalHistoryBundle(problemDtos, surgicalDtos, directiveDtos, sectionSensitive);
    }

    private int resolveNotesLimit(Integer requestedLimit, int fallback) {
        if (requestedLimit == null) {
            return fallback;
        }
        return resolveTimelineLimit(requestedLimit);
    }

    private boolean isSensitiveProblem(PatientProblem problem) {
        if (problem == null) {
            return false;
        }
        return containsSensitiveKeyword(problem.getProblemDisplay())
            || containsSensitiveKeyword(problem.getNotes());
    }

    private boolean isSensitiveSurgicalHistory(PatientSurgicalHistory history) {
        if (history == null) {
            return false;
        }
        return containsSensitiveKeyword(history.getProcedureDisplay())
            || containsSensitiveKeyword(history.getNotes());
    }

    private boolean isSensitiveAdvanceDirective(AdvanceDirective directive) {
        if (directive == null) {
            return false;
        }
        return containsSensitiveKeyword(directive.getDescription());
    }

    private boolean isSensitiveNursingNote(NursingNote note) {
        if (note == null) {
            return false;
        }
        return containsSensitiveKeyword(note.getNarrative())
            || containsSensitiveKeyword(note.getDataSubjective())
            || containsSensitiveKeyword(note.getDataObjective())
            || containsSensitiveKeyword(note.getDataAssessment())
            || containsSensitiveKeyword(note.getDataPlan())
            || containsSensitiveKeyword(note.getDataImplementation())
            || containsSensitiveKeyword(note.getDataEvaluation())
            || containsSensitiveKeyword(note.getActionSummary())
            || containsSensitiveKeyword(note.getResponseSummary())
            || containsSensitiveKeyword(note.getEducationSummary());
    }

    private boolean isSensitiveUltrasoundOrder(UltrasoundOrder order) {
        if (order == null) {
            return false;
        }
        return Boolean.TRUE.equals(order.getIsHighRiskPregnancy())
            || containsSensitiveKeyword(order.getClinicalIndication())
            || containsSensitiveKeyword(order.getHighRiskNotes())
            || containsSensitiveKeyword(order.getSpecialInstructions());
    }

    private boolean isSensitiveUltrasoundReport(UltrasoundReport report) {
        if (report == null) {
            return false;
        }
        return Boolean.TRUE.equals(report.getAnomaliesDetected())
            || Boolean.TRUE.equals(report.getSpecialistReferralNeeded())
            || containsSensitiveKeyword(report.getFindingsSummary())
            || containsSensitiveKeyword(report.getInterpretation())
            || containsSensitiveKeyword(report.getAnomalyDescription())
            || containsSensitiveKeyword(report.getFollowUpRecommendations())
            || containsSensitiveKeyword(report.getGeneticScreeningType());
    }

    private void logDoctorRecordAudit(
        Patient patient,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        String reason,
        boolean includeSensitive,
        DoctorPatientRecordDTO response,
        Set<String> sensitiveSections
    ) {
        try {
            UUID effectiveUserId = requesterUserId != null
                ? requesterUserId
                : Optional.ofNullable(assignment.getUser()).map(User::getId).orElse(null);
            if (effectiveUserId == null) {
                log.warn("Unable to resolve user id for doctor record audit log.");
                return;
            }
            Map<String, Object> details = new HashMap<>();
            putIfNotNull(details, "reason", reason);
            details.put("includeSensitive", includeSensitive);
            details.put("sensitiveSections", new ArrayList<>(sensitiveSections));
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("allergies", sizeOf(response.getAllergies()));
            counts.put("medications", sizeOf(response.getMedications()));
            counts.put("labResults", sizeOf(response.getLabResults()));
            counts.put("imagingOrders", sizeOf(response.getImagingOrders()));
            counts.put("imagingReports", sizeOf(response.getImagingReports()));
            counts.put("notes", sizeOf(response.getNotes()));
            counts.put("recentEncounters", sizeOf(response.getRecentEncounters()));
            counts.put("problems", sizeOf(response.getProblems()));
            counts.put("surgicalHistory", sizeOf(response.getSurgicalHistory()));
            counts.put("advanceDirectives", sizeOf(response.getAdvanceDirectives()));
            details.put("sectionCounts", counts);

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(effectiveUserId)
                .assignmentId(assignment.getId())
                .userName(resolveUserDisplayName(assignment))
                .roleName(assignment.getRole() != null ? assignment.getRole().getName() : null)
                .hospitalName(assignment.getHospital() != null ? assignment.getHospital().getName() : null)
                .resourceId(patient.getId() != null ? patient.getId().toString() : null)
                .resourceName(resolvePatientName(patient))
                .entityType("PATIENT")
                .eventType(AuditEventType.PATIENT_ACCESS)
                .status(AuditStatus.SUCCESS)
                .eventDescription("Doctor record view")
                .details(details)
                .build();
            auditEventLogService.logEvent(auditEvent);
        } catch (Exception ex) {
            log.warn("Failed to log doctor record access", ex);
        }
    }

    private int severityOrder(AllergySeverity severity) {
        if (severity == null) {
            return 3;
        }
        return switch (severity) {
            case LIFE_THREATENING -> 0;
            case SEVERE -> 1;
            case MODERATE -> 2;
            default -> 3;
        };
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private record MedicalHistoryBundle(
        List<PatientProblemResponseDTO> problems,
        List<PatientSurgicalHistoryResponseDTO> surgicalHistory,
        List<AdvanceDirectiveResponseDTO> advanceDirectives,
        boolean sensitive
    ) {}

    private record ImagingBundle(
        List<UltrasoundOrderResponseDTO> orders,
        List<UltrasoundReportResponseDTO> reports,
        boolean sensitive
    ) {}

    private boolean shouldIncludeCategory(Set<String> filters, String category) {
        if (category == null || filters == null || filters.isEmpty()) {
            return true;
        }
        return filters.contains(category);
    }

    private String formatEncounterSummary(Encounter encounter) {
        String type = encounter.getEncounterType() != null ? encounter.getEncounterType().name() : "Encounter";
        StringBuilder summary = new StringBuilder(type);
        if (encounter.getDepartment() != null && encounter.getDepartment().getName() != null) {
            summary.append(" · ").append(encounter.getDepartment().getName());
        }
        if (encounter.getNotes() != null && !encounter.getNotes().isBlank()) {
            summary.append(" — ").append(truncate(encounter.getNotes()));
        }
        return summary.toString();
    }

    private String formatPrescriptionSummary(Prescription prescription) {
        String medication = Optional.ofNullable(prescription.getMedicationDisplayName())
            .orElse(prescription.getMedicationName());
        StringBuilder summary = new StringBuilder(medication != null ? medication : "Prescription");
        if (prescription.getDosage() != null) {
            summary.append(" · ").append(prescription.getDosage());
        }
        if (prescription.getFrequency() != null) {
            summary.append(" · ").append(prescription.getFrequency());
        }
        return summary.toString();
    }

    private String formatLabResultSummary(LabResult result) {
        String testName = Optional.ofNullable(result.getLabOrder())
            .map(order -> order.getClinicalIndication())
            .filter(s -> s != null && !s.isBlank())
            .orElse("Lab Result");
        StringBuilder summary = new StringBuilder(testName);
        summary.append(": ").append(result.getResultValue());
        if (result.getResultUnit() != null && !result.getResultUnit().isBlank()) {
            summary.append(" ").append(result.getResultUnit());
        }
        return summary.toString();
    }

    private String formatAllergySummary(PatientAllergy allergy) {
        String allergen = Optional.ofNullable(allergy.getAllergenDisplay()).orElse("Allergy");
    String severity = allergy.getSeverity() != null ? allergy.getSeverity().name() : LOG_UNKNOWN;
        return allergen + " (" + severity + ")";
    }

    private String formatImagingOrderSummary(UltrasoundOrder order) {
        String modality = Optional.ofNullable(order.getScanType())
            .map(Enum::name)
            .orElse("Imaging Order");
        StringBuilder summary = new StringBuilder(modality);
        if (order.getClinicalIndication() != null && !order.getClinicalIndication().isBlank()) {
            summary.append(" · ").append(truncate(order.getClinicalIndication()));
        }
        return summary.toString();
    }

    private String formatImagingReportSummary(UltrasoundReport report) {
        String scan = Optional.ofNullable(report.getUltrasoundOrder())
            .map(UltrasoundOrder::getScanType)
            .map(Enum::name)
            .orElse("Imaging Report");
        StringBuilder summary = new StringBuilder(scan);
        if (report.getFindingsSummary() != null && !report.getFindingsSummary().isBlank()) {
            summary.append(" — ").append(truncate(report.getFindingsSummary()));
        }
        return summary.toString();
    }

    private String formatProcedureSummary(PatientSurgicalHistory history) {
        String procedure = Optional.ofNullable(history.getProcedureDisplay()).orElse("Procedure");
        StringBuilder summary = new StringBuilder(procedure);
        if (history.getProcedureDate() != null) {
            summary.append(" · ").append(history.getProcedureDate());
        }
        return summary.toString();
    }

    private boolean isSensitiveEncounter(Encounter encounter) {
        if (encounter == null) {
            return false;
        }
        if (encounter.getDepartment() != null && encounter.getDepartment().getName() != null) {
            String deptName = encounter.getDepartment().getName().toLowerCase(Locale.ROOT);
            if (SENSITIVE_DEPARTMENTS.contains(deptName)) {
                return true;
            }
        }
        return containsSensitiveKeyword(encounter.getNotes());
    }

    private boolean isSensitiveMedication(Prescription prescription) {
        if (prescription == null) {
            return false;
        }
        return containsSensitiveKeyword(prescription.getMedicationName())
            || containsSensitiveKeyword(prescription.getMedicationDisplayName())
            || containsSensitiveKeyword(prescription.getNotes())
            || containsHighAlertKeyword(prescription.getMedicationName());
    }

    private boolean isSensitiveLabResult(LabResult result) {
        if (result == null) {
            return false;
        }
        String clinicalContext = Optional.ofNullable(result.getLabOrder())
            .map(order -> order.getClinicalIndication())
            .orElse(null);
        return containsSensitiveKeyword(clinicalContext) || containsSensitiveKeyword(result.getNotes());
    }

    private boolean isSensitiveAllergy(PatientAllergy allergy) {
        if (allergy == null) {
            return false;
        }
        if (allergy.getSeverity() == AllergySeverity.LIFE_THREATENING) {
            return true;
        }
        return containsSensitiveKeyword(allergy.getReaction()) || containsSensitiveKeyword(allergy.getReactionNotes());
    }

    private boolean containsHighAlertKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return HIGH_ALERT_MEDICATION_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean containsSensitiveKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private String resolveStaffName(Encounter encounter) {
        return resolveStaffDisplayName(encounter.getStaff());
    }

    private String resolveStaffDisplayName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String staffName = staff.getFullName();
        if (staffName != null && !staffName.isBlank()) {
            return staffName;
        }
        return staff.getName();
    }

    private String resolvePatientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        String first = patient.getFirstName() != null ? patient.getFirstName().trim() : "";
        String last = patient.getLastName() != null ? patient.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? patient.getEmail() : full;
    }

    private String normalizeAccessReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private int resolveTimelineLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit < 1) {
            return DEFAULT_TIMELINE_LIMIT;
        }
        return Math.min(MAX_TIMELINE_LIMIT, requestedLimit);
    }

    private Set<String> normalizeCategoryFilters(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return Collections.emptySet();
        }
        return categories.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(String::toUpperCase)
            .collect(Collectors.toSet());
    }

    private void logTimelineAudit(Patient patient, UUID requesterUserId, UserRoleHospitalAssignment assignment,
                                  String reason, boolean includeSensitive, int entryCount) {
        try {
            UUID effectiveUserId = requesterUserId != null
                ? requesterUserId
                : Optional.ofNullable(assignment.getUser()).map(User::getId).orElse(null);
            if (effectiveUserId == null) {
                log.warn("Unable to resolve user id for patient timeline audit log.");
                return;
            }
            Map<String, Object> details = new HashMap<>();
            putIfNotNull(details, "reason", reason);
            details.put("includeSensitive", includeSensitive);
            details.put("entries", entryCount);

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(effectiveUserId)
                .assignmentId(assignment.getId())
                .userName(resolveUserDisplayName(assignment))
                .roleName(assignment.getRole() != null ? assignment.getRole().getName() : null)
                .hospitalName(assignment.getHospital() != null ? assignment.getHospital().getName() : null)
                .resourceId(patient.getId() != null ? patient.getId().toString() : null)
                .resourceName(resolvePatientName(patient))
                .entityType("PATIENT")
                .eventType(AuditEventType.PATIENT_ACCESS)
                .status(AuditStatus.SUCCESS)
                .eventDescription("Doctor timeline view")
                .details(details)
                .build();
            auditEventLogService.logEvent(auditEvent);
        } catch (Exception ex) {
            log.warn("Failed to log doctor timeline access", ex);
        }
    }

    private String resolveUserDisplayName(UserRoleHospitalAssignment assignment) {
        if (assignment == null || assignment.getUser() == null) {
            return null;
        }
        User user = assignment.getUser();
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        if (!full.isEmpty()) {
            return full;
        }
        return user.getUsername();
    }

    private LocalDateTime coalesce(LocalDateTime primary, LocalDateTime fallback) {
        return primary != null ? primary : fallback;
    }

    private LocalDateTime toDateTime(LocalDate date) {
        return date != null ? date.atStartOfDay() : null;
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (metadata != null && key != null && value != null) {
            metadata.put(key, value);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 157) + "…" : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String buildLowercaseContainsPattern(String input) {
        String normalized = trimToNull(input);
        if (normalized == null) {
            return null;
        }
        String escaped = escapeLike(normalized.toLowerCase(Locale.ROOT));
        return "%" + escaped + "%";
    }

    private static String buildPhoneSearchPattern(String input) {
        String normalized = trimToNull(input);
        if (normalized == null) {
            return null;
        }
        return "%" + escapeLike(normalized) + "%";
    }

    private static String escapeLike(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    private PatientResponseDTO buildPatientDto(Patient patient, UUID hospitalId) {
        PatientResponseDTO dto = patientMapper.toPatientDTO(patient, hospitalId);
        if (patient == null || dto == null || patient.getId() == null) {
            return dto;
        }

        patientVitalSignService.getLatestSnapshot(patient.getId(), hospitalId)
            .ifPresent(snapshot -> {
                dto.setLastVitals(snapshot);
                if (snapshot.getHeartRate() != null) {
                    dto.setHr(snapshot.getHeartRate());
                }
                if (snapshot.getBloodPressure() != null) {
                    dto.setBp(snapshot.getBloodPressure());
                }
                if (snapshot.getSpo2() != null) {
                    dto.setSpo2(snapshot.getSpo2());
                }
            });

        return dto;
    }

    private String generateMrn(Hospital hospital) {
        if (hospital == null || hospital.getId() == null) {
            throw new IllegalArgumentException("Hospital context required to generate MRN");
        }

        String prefix = deriveHospitalPrefix(hospital);
        UUID hospitalId = hospital.getId();

        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = prefix + String.format("%04d", SECURE_RANDOM.nextInt(0, 10_000));
            if (!registrationRepository.existsByMrnAndHospitalId(candidate, hospitalId)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Unable to generate unique MRN for hospital " + hospitalId);
    }

    private String deriveHospitalPrefix(Hospital hospital) {
        if (hospital == null) {
            return DEFAULT_PREFIX;
        }
        String prefix = sanitizePrefix(hospital.getCode());
        if (prefix != null) {
            return prefix;
        }
        prefix = sanitizePrefix(hospital.getName());
        if (prefix != null) {
            return prefix;
        }
        return DEFAULT_PREFIX;
    }

    private String sanitizePrefix(String value) {
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

    private boolean applyNullable(String value, Consumer<String> setter) {
        if (setter == null || value == null) {
            return false;
        }
        String normalized = trimToNull(value);
        setter.accept(normalized);
        return true;
    }

    private String buildMailingAddress(String line1, String line2, String city, String state, String postalCode, String country) {
        List<String> segments = new ArrayList<>();
        if (line1 != null && !line1.isBlank()) {
            segments.add(line1.trim());
        }
        if (line2 != null && !line2.isBlank()) {
            segments.add(line2.trim());
        }
        String cityState = Stream.of(city, state)
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(", "));
        if (!cityState.isBlank()) {
            segments.add(cityState);
        }
        if (postalCode != null && !postalCode.isBlank()) {
            segments.add(postalCode.trim());
        }
        if (country != null && !country.isBlank()) {
            segments.add(country.trim());
        }
        if (segments.isEmpty()) {
            return null;
        }
        return String.join(" | ", segments);
    }

    private String joinChronicConditions(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .collect(Collectors.joining(","));
    }
}
