package com.example.hms.service.impl;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientProblem;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.ActiveEncounterDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.AllergySummaryDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.CodeStatusDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.DirectiveSummaryDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.PatientHeaderDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO.ProblemSummaryDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.PatientStoryboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregates allergies, active problems, the most recent non-terminal encounter,
 * and the resuscitation/advance-directive state for the persistent Storyboard
 * banner. All reads are issued against the existing clinical tables; no
 * schema changes are introduced.
 */
@Service
@RequiredArgsConstructor
public class PatientStoryboardServiceImpl implements PatientStoryboardService {

    /** Cap each chip list to keep the banner compact on mobile. */
    static final int MAX_ALLERGIES = 8;
    static final int MAX_PROBLEMS = 8;
    static final int MAX_DIRECTIVES = 5;

    private static final Set<EncounterStatus> TERMINAL_ENCOUNTER_STATUSES =
        Set.of(EncounterStatus.COMPLETED, EncounterStatus.CANCELLED);

    private static final Set<AllergySeverity> HIGH_SEVERITY =
        Set.of(AllergySeverity.SEVERE, AllergySeverity.LIFE_THREATENING);

    private final PatientRepository patientRepository;
    private final PatientAllergyRepository allergyRepository;
    private final PatientProblemRepository problemRepository;
    private final EncounterRepository encounterRepository;
    private final AdvanceDirectiveRepository advanceDirectiveRepository;
    private final HospitalRepository hospitalRepository;

    @Override
    @Transactional(readOnly = true)
    public PatientStoryboardDTO getStoryboard(UUID patientId, UUID hospitalId) {
        if (patientId == null) {
            throw new ResourceNotFoundException("patient.notFound", "<null>");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));

        List<AllergySummaryDTO> allergies = loadAllergies(patientId, hospitalId);
        List<ProblemSummaryDTO> problems = loadProblems(patientId, hospitalId);
        ActiveEncounterDTO activeEncounter = loadActiveEncounter(patientId, hospitalId);
        CodeStatusDTO codeStatus = loadCodeStatus(patient, hospitalId);

        boolean highSeverityAllergy = allergies.stream()
            .anyMatch(a -> a.getSeverity() != null
                && HIGH_SEVERITY.contains(safeAllergySeverity(a.getSeverity())));
        boolean chronicProblem = problems.stream().anyMatch(ProblemSummaryDTO::isChronic);

        return PatientStoryboardDTO.builder()
            .patient(buildHeader(patient, hospitalId))
            .allergies(allergies)
            .problems(problems)
            .activeEncounter(activeEncounter)
            .codeStatus(codeStatus)
            .hasHighSeverityAllergy(highSeverityAllergy)
            .hasChronicProblem(chronicProblem)
            .hospitalId(hospitalId)
            .hospitalName(resolveHospitalName(hospitalId))
            .generatedAt(LocalDateTime.now())
            .build();
    }

    private PatientHeaderDTO buildHeader(Patient patient, UUID hospitalId) {
        LocalDate dob = patient.getDateOfBirth();
        Integer age = dob != null ? Period.between(dob, LocalDate.now()).getYears() : null;
        String mrn = hospitalId != null ? patient.getMrnForHospital(hospitalId) : null;
        if (mrn == null) {
            // Fall back to the primary registration MRN so the banner still has an identifier
            // even if no explicit hospital scope was supplied.
            Hospital primary = patient.getPrimaryHospital();
            if (primary != null && primary.getId() != null) {
                mrn = patient.getMrnForHospital(primary.getId());
            }
        }
        return PatientHeaderDTO.builder()
            .id(patient.getId())
            .firstName(patient.getFirstName())
            .lastName(patient.getLastName())
            .fullName(patient.getFullName())
            .mrn(mrn)
            .dateOfBirth(dob)
            .ageYears(age)
            .gender(patient.getGender())
            .bloodType(patient.getBloodType())
            .build();
    }

    private List<AllergySummaryDTO> loadAllergies(UUID patientId, UUID hospitalId) {
        List<PatientAllergy> source = hospitalId != null
            ? allergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : allergyRepository.findByPatient_Id(patientId);
        return source.stream()
            .filter(PatientAllergy::isActive)
            .sorted(Comparator
                .comparing(PatientStoryboardServiceImpl::severityRank).reversed()
                .thenComparing(a -> Optional.ofNullable(a.getRecordedDate()).orElse(LocalDate.MIN),
                    Comparator.reverseOrder()))
            .limit(MAX_ALLERGIES)
            .map(this::toAllergyDto)
            .toList();
    }

    private List<ProblemSummaryDTO> loadProblems(UUID patientId, UUID hospitalId) {
        List<PatientProblem> source = hospitalId != null
            ? problemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : problemRepository.findByPatient_Id(patientId);
        return source.stream()
            .filter(p -> p.getStatus() == null
                || p.getStatus() == ProblemStatus.ACTIVE
                || p.getStatus() == ProblemStatus.RECURRENCE)
            .sorted(Comparator
                .comparing(PatientProblem::isChronic).reversed()
                .thenComparing(p -> Optional.ofNullable(p.getOnsetDate()).orElse(LocalDate.MIN),
                    Comparator.reverseOrder()))
            .limit(MAX_PROBLEMS)
            .map(this::toProblemDto)
            .toList();
    }

    private ActiveEncounterDTO loadActiveEncounter(UUID patientId, UUID hospitalId) {
        if (hospitalId == null) {
            // Without a hospital scope we cannot use the existing repository helper, so fall
            // back to scanning the patient's encounter list and picking the most recent
            // non-terminal one.
            return encounterRepository.findByPatient_Id(patientId).stream()
                .filter(e -> !TERMINAL_ENCOUNTER_STATUSES.contains(e.getStatus()))
                .max(Comparator.comparing(Encounter::getEncounterDate,
                    Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(this::toEncounterDto)
                .orElse(null);
        }
        List<Encounter> active = encounterRepository
            .findByPatient_IdAndHospital_IdAndStatusNotIn(patientId, hospitalId, TERMINAL_ENCOUNTER_STATUSES);
        return active.stream()
            .max(Comparator.comparing(Encounter::getEncounterDate,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(this::toEncounterDto)
            .orElse(null);
    }

    private CodeStatusDTO loadCodeStatus(Patient patient, UUID hospitalId) {
        List<AdvanceDirective> directives = hospitalId != null
            ? advanceDirectiveRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospitalId)
            : advanceDirectiveRepository.findByPatient_Id(patient.getId());
        List<DirectiveSummaryDTO> activeDirectives = directives.stream()
            .filter(d -> d.getStatus() == null || d.getStatus() == AdvanceDirectiveStatus.ACTIVE)
            .sorted(Comparator
                .comparing((AdvanceDirective d) -> Optional.ofNullable(d.getEffectiveDate()).orElse(LocalDate.MIN))
                .reversed())
            .limit(MAX_DIRECTIVES)
            .map(this::toDirectiveDto)
            .toList();
        if ((patient.getCodeStatus() == null || patient.getCodeStatus().isBlank())
            && activeDirectives.isEmpty()) {
            return null;
        }
        return CodeStatusDTO.builder()
            .status(patient.getCodeStatus())
            .directives(activeDirectives)
            .build();
    }

    private AllergySummaryDTO toAllergyDto(PatientAllergy a) {
        return AllergySummaryDTO.builder()
            .id(a.getId())
            .allergenDisplay(a.getAllergenDisplay())
            .allergenCode(a.getAllergenCode())
            .severity(a.getSeverity() != null ? a.getSeverity().name() : null)
            .verificationStatus(a.getVerificationStatus() != null ? a.getVerificationStatus().name() : null)
            .reaction(a.getReaction())
            .build();
    }

    private ProblemSummaryDTO toProblemDto(PatientProblem p) {
        return ProblemSummaryDTO.builder()
            .id(p.getId())
            .problemDisplay(p.getProblemDisplay())
            .problemCode(p.getProblemCode())
            .icdVersion(p.getIcdVersion())
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .severity(p.getSeverity() != null ? p.getSeverity().name() : null)
            .chronic(p.isChronic())
            .onsetDate(p.getOnsetDate())
            .build();
    }

    private ActiveEncounterDTO toEncounterDto(Encounter e) {
        return ActiveEncounterDTO.builder()
            .id(e.getId())
            .code(e.getCode())
            .encounterType(e.getEncounterType() != null ? e.getEncounterType().name() : null)
            .status(e.getStatus() != null ? e.getStatus().name() : null)
            .encounterDate(e.getEncounterDate())
            .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
            .staffFullName(e.getStaff() != null ? e.getStaff().getFullName() : null)
            .roomAssignment(e.getRoomAssignment())
            .chiefComplaint(e.getChiefComplaint())
            .build();
    }

    private DirectiveSummaryDTO toDirectiveDto(AdvanceDirective d) {
        return DirectiveSummaryDTO.builder()
            .id(d.getId())
            .directiveType(d.getDirectiveType() != null ? d.getDirectiveType().name() : null)
            .status(d.getStatus() != null ? d.getStatus().name() : null)
            .effectiveDate(d.getEffectiveDate())
            .expirationDate(d.getExpirationDate())
            .description(d.getDescription())
            .build();
    }

    private String resolveHospitalName(UUID hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        return hospitalRepository.findById(hospitalId)
            .map(Hospital::getName)
            .orElse(null);
    }

    private static int severityRank(PatientAllergy a) {
        AllergySeverity sev = a.getSeverity();
        if (sev == null) return 0;
        return switch (sev) {
            case LIFE_THREATENING -> 4;
            case SEVERE -> 3;
            case MODERATE -> 2;
            case MILD -> 1;
            case UNKNOWN -> 0;
        };
    }

    private static AllergySeverity safeAllergySeverity(String name) {
        try {
            return AllergySeverity.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
