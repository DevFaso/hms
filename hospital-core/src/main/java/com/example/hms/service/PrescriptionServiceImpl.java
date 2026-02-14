package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.model.PatientAllergy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PrescriptionServiceImpl implements PrescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(PrescriptionServiceImpl.class);

    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final PatientAllergyRepository patientAllergyRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final PrescriptionMapper prescriptionMapper;
    private final RoleValidator roleValidator;
    private final AuthService authService; // exposes UUID getCurrentUserId()
    private final UserRoleHospitalAssignmentRepository urhaRepository;

    @Override
    @Transactional
    public PrescriptionResponseDTO createPrescription(PrescriptionRequestDTO request, Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();
        Patient patient = resolvePatient(request, locale);

    Staff staff = resolveStaffContext(request, currentUserId);
    Encounter encounter = resolveEncounterContext(request, patient, staff);

        ensureContextConsistency(patient, staff, encounter);

        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        if (!roleValidator.canCreatePrescription(currentUserId, hospitalId)) {
            throw new BusinessException("prescription.only.doctor.admin");
        }

        UserRoleHospitalAssignment prescriberAssignment =
            resolvePrescriberAssignmentOrThrow(staff, encounter, hospitalId);

        // DECISION SUPPORT: Check allergies before prescribing
        checkAllergyConflicts(patient, hospitalId, request.getMedicationName(), request.getForceOverride());

        Prescription entity = prescriptionMapper.toEntity(request, patient, staff, encounter);
        entity.setAssignment(prescriberAssignment);

        Prescription saved = prescriptionRepository.save(entity);
        return prescriptionMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO getPrescriptionById(UUID id, Locale locale) {
        return prescriptionRepository.findById(id)
            .map(prescriptionMapper::toResponseDTO)
            .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));
    }

    @Override
    @Transactional
    public Page<PrescriptionResponseDTO> list(UUID patientId, UUID staffId, UUID encounterId, Pageable pageable, Locale locale) {
        if (patientId != null) {
            return prescriptionRepository.findByPatient_Id(patientId, pageable)
                .map(prescriptionMapper::toResponseDTO);
        }
        if (staffId != null) {
            return prescriptionRepository.findByStaff_Id(staffId, pageable)
                .map(prescriptionMapper::toResponseDTO);
        }
        if (encounterId != null) {
            return prescriptionRepository.findByEncounter_Id(encounterId, pageable)
                .map(prescriptionMapper::toResponseDTO);
        }
        return prescriptionRepository.findAll(pageable).map(prescriptionMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public PrescriptionResponseDTO updatePrescription(UUID id, PrescriptionRequestDTO request, Locale locale) {
        Prescription existing = prescriptionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));

        UUID currentUserId = authService.getCurrentUserId();

        Patient patient = resolvePatient(request, locale);

    Staff staff = resolveStaffContext(request, currentUserId);
    Encounter encounter = resolveEncounterContext(request, patient, staff);

        ensureContextConsistency(patient, staff, encounter);

        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        if (!roleValidator.canCreatePrescription(currentUserId, hospitalId)) {
            throw new BusinessException("prescription.only.doctor.admin");
        }

        UserRoleHospitalAssignment prescriberAssignment =
            resolvePrescriberAssignmentOrThrow(staff, encounter, hospitalId);

        // DECISION SUPPORT: Check allergies before updating prescription
        checkAllergyConflicts(patient, hospitalId, request.getMedicationName(), request.getForceOverride());

        prescriptionMapper.updateEntity(existing, request, patient, staff, encounter);
        existing.setAssignment(prescriberAssignment);

        Prescription saved = prescriptionRepository.save(existing);
        return prescriptionMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deletePrescription(UUID id, Locale locale) {
        if (!prescriptionRepository.existsById(id)) {
            throw new ResourceNotFoundException("prescription.notfound");
        }
        prescriptionRepository.deleteById(id);
    }

    @Override
    @Transactional
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsByPatientId(UUID patientId, Locale locale) {
        return prescriptionRepository.findByPatient_Id(patientId, Pageable.unpaged())
            .getContent().stream()
            .map(prescriptionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsByStaffId(UUID staffId, Locale locale) {
        return prescriptionRepository.findByStaff_Id(staffId, Pageable.unpaged())
            .getContent().stream()
            .map(prescriptionMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public java.util.List<PrescriptionResponseDTO> getPrescriptionsByEncounterId(UUID encounterId, Locale locale) {
        return prescriptionRepository.findByEncounter_Id(encounterId, Pageable.unpaged())
            .getContent().stream()
            .map(prescriptionMapper::toResponseDTO)
            .toList();
    }

    /* ============================
       Helpers
       ============================ */

    @SuppressWarnings("java:S1172") // locale reserved for i18n error messages
    private Patient resolvePatient(PrescriptionRequestDTO request, Locale locale) {
        if (request.getPatientId() != null) {
            return patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
        }
        if (StringUtils.hasText(request.getPatientIdentifier())) {
            String identifier = request.getPatientIdentifier().trim();
            Optional<Patient> viaUsernameOrEmail = patientRepository.findByUsernameOrEmail(identifier);
            if (viaUsernameOrEmail.isPresent()) {
                return viaUsernameOrEmail.get();
            }

            List<Patient> byMrn = patientRepository.findByMrn(identifier);
            if (!byMrn.isEmpty()) {
                return byMrn.get(0);
            }

            try {
                UUID parsed = UUID.fromString(identifier);
                return patientRepository.findById(parsed)
                    .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
            } catch (IllegalArgumentException ignore) {
                // not a UUID, fall through
            }

            throw new ResourceNotFoundException("patient.notfound");
        }
        throw new BusinessException("prescription.patient.required");
    }

    private void ensureContextConsistency(Patient patient, Staff staff, Encounter encounter) {
        if (encounter.getHospital() == null) {
            throw new BusinessException("prescription.hospital.link.required");
        }
        if (encounter.getPatient() == null || !encounter.getPatient().getId().equals(patient.getId())) {
            throw new BusinessException("prescription.encounter.patient.mismatch");
        }
        if (encounter.getStaff() == null || !encounter.getStaff().getId().equals(staff.getId())) {
            throw new BusinessException("prescription.encounter.staff.mismatch");
        }
        if (staff.getHospital() == null || !staff.getHospital().getId().equals(encounter.getHospital().getId())) {
            throw new BusinessException("prescription.encounter.staff.hospital.mismatch");
        }
    }

    /**
     * Determine the prescriber's assignment to satisfy NOT NULL FK:
     * 1) encounter.assignment (best, immutable snapshot)
     * 2) staff.assignment / URHA lookup for doctor role within the hospital scope
     */
    private UserRoleHospitalAssignment resolvePrescriberAssignmentOrThrow(Staff staff, Encounter encounter, UUID hospitalId) {
        if (encounter != null && encounter.getAssignment() != null) {
            return encounter.getAssignment();
        }
        return resolveAssignmentForStaff(staff, hospitalId);
    }

    private Staff resolveStaffContext(PrescriptionRequestDTO request, UUID currentUserId) {
        if (request.getStaffId() != null) {
            return staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));
        }
        if (currentUserId == null) {
            throw new BusinessException("prescription.staff.context.missing");
        }
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId)
            .orElseThrow(() -> new BusinessException("prescription.staff.context.missing"));
    }

    private Encounter resolveEncounterContext(PrescriptionRequestDTO request,
                                              Patient patient,
                                              Staff staff) {
        if (request.getEncounterId() != null) {
            return encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound"));
        }

        UUID hospitalId = determineHospitalId(staff, patient);
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        Optional<Encounter> existing = encounterRepository
            .findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
                patient.getId(), staff.getId(), hospitalId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserRoleHospitalAssignment assignment = resolveAssignmentForStaff(staff, hospitalId);
        return createEncounterSnapshot(patient, staff, assignment);
    }

    private UUID determineHospitalId(Staff staff, Patient patient) {
        if (staff != null && staff.getHospital() != null) {
            return staff.getHospital().getId();
        }
        if (patient != null && patient.getHospitalId() != null) {
            return patient.getHospitalId();
        }
        return roleValidator.getCurrentHospitalId();
    }

    private Encounter createEncounterSnapshot(Patient patient,
                                              Staff staff,
                                              UserRoleHospitalAssignment assignment) {
        if (staff.getHospital() == null) {
            throw new BusinessException("prescription.staff.hospital.missing");
        }
        Encounter encounter = Encounter.builder()
            .patient(patient)
            .staff(staff)
            .hospital(staff.getHospital())
            .assignment(assignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now())
            .status(EncounterStatus.IN_PROGRESS)
            .notes("Auto-generated for prescription entry")
            .build();
        encounter.setCode(generateEncounterCode());
        return encounterRepository.save(encounter);
    }

    private String generateEncounterCode() {
        String date = LocalDate.now().toString().replace("-", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "ENC-" + date + "-" + suffix;
    }

    private UserRoleHospitalAssignment resolveAssignmentForStaff(Staff staff, UUID hospitalId) {
        if (staff == null) {
            throw new BusinessException("prescription.staff.notfound");
        }
        if (hospitalId == null) {
            throw new BusinessException("prescription.hospital.context.missing");
        }

        UserRoleHospitalAssignment fromStaff = staff.getAssignment();
        if (fromStaff != null && fromStaff.getHospital() != null
            && hospitalId.equals(fromStaff.getHospital().getId())) {
            return fromStaff;
        }

        UUID staffUserId = staff.getUser() != null ? staff.getUser().getId() : null;
        if (staffUserId == null) {
            throw new BusinessException("prescription.assignment.missing.staff.user");
        }

        Optional<UserRoleHospitalAssignment> viaDoctor =
            urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(staffUserId, hospitalId, "DOCTOR");
        if (viaDoctor.isEmpty()) {
            viaDoctor = urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(staffUserId, hospitalId, "ROLE_DOCTOR");
        }

        return viaDoctor.orElseThrow(() -> new BusinessException("prescription.assignment.missing"));
    }

    /**
     * Check patient allergies against the medication being prescribed.
     * Logs warnings and throws BusinessException if severe allergy found without override.
     */
    private void checkAllergyConflicts(Patient patient, UUID hospitalId, String medicationName, Boolean forceOverride) {
        if (medicationName == null || medicationName.isBlank()) {
            return; // Cannot check without medication name
        }

        List<PatientAllergy> allergies = patientAllergyRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospitalId);
        String medLower = medicationName.toLowerCase();
        
        for (PatientAllergy allergy : allergies) {
            if (allergy.isActive() && matchesAllergen(medLower, allergy.getAllergenDisplay())) {
                handleAllergyConflict(patient, allergy, medicationName, forceOverride);
            }
        }
    }

    private boolean matchesAllergen(String medLower, String allergenDisplay) {
        if (allergenDisplay == null) {
            return false;
        }
        String allergenLower = allergenDisplay.toLowerCase();
        return allergenLower.contains(medLower) || medLower.contains(allergenLower);
    }

    private void handleAllergyConflict(Patient patient, PatientAllergy allergy, String medicationName, Boolean forceOverride) {
        String severityStr = allergy.getSeverity() != null ? allergy.getSeverity().name() : "UNKNOWN";
        String reaction = allergy.getReaction() != null ? allergy.getReaction() : "unspecified reaction";
        
        logger.warn("ALLERGY ALERT: Patient {} has documented allergy to '{}' (severity: {}, reaction: {}). " +
                   "Attempted to prescribe '{}'. Override: {}",
                   patient.getId(), allergy.getAllergenDisplay(), severityStr, reaction, medicationName, forceOverride);
        
        if (isSevereAllergy(allergy.getSeverity()) && !Boolean.TRUE.equals(forceOverride)) {
            throw new BusinessException(
                String.format("ALLERGY CONFLICT: Patient has a documented %s allergy to '%s' with reaction: %s. " +
                            "Cannot prescribe '%s' without explicit override. Review patient allergies before proceeding.",
                            severityStr, allergy.getAllergenDisplay(), reaction, medicationName)
            );
        }
        
        logger.info("Prescription proceeding with documented {} allergy to '{}'", severityStr, allergy.getAllergenDisplay());
    }

    private boolean isSevereAllergy(com.example.hms.enums.AllergySeverity severity) {
        if (severity == null) {
            return false;
        }
        return severity == com.example.hms.enums.AllergySeverity.SEVERE 
            || severity == com.example.hms.enums.AllergySeverity.LIFE_THREATENING;
    }
}

