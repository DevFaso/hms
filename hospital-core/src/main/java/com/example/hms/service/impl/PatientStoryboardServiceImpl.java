package com.example.hms.service.impl;

import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.WithheldRows;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.ProblemStatus;
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
import com.example.hms.service.PatientStoryboardService;
import com.example.hms.service.support.PatientChartAccess;
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
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import java.util.Map;
import com.example.hms.service.recordaccess.BreakGlassGate;

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

    private final PatientChartAccess patientChartAccess;
    private final PatientAllergyRepository allergyRepository;
    private final PatientProblemRepository problemRepository;
    private final EncounterRepository encounterRepository;
    private final AdvanceDirectiveRepository advanceDirectiveRepository;
    private final HospitalRepository hospitalRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final SensitivityClassifier sensitivityClassifier;
    private final CrossHospitalReachRecorder reachRecorder;
    private final BreakGlassGate breakGlassGate;

    @Override
    @Transactional(readOnly = true)
    public PatientStoryboardDTO getStoryboard(UUID patientId, UUID hospitalId) {
        // See PatientChartAccess: the tenant-scoped findById 404'd the whole
        // storyboard for cross-hospital patients ("Impossible de charger le
        // résumé patient" on a chart that had otherwise rendered).
        Patient patient = patientChartAccess.require(patientId, hospitalId);

        // E9 #59 — the hospitals this caller may read for this patient; the
        // acting hospital alone when the policy says so, more when the patient
        // is registered here. Null scope (super-admin global view) reads all.
        UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = hospitalId == null ? null
            : recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
        // E9 #62 — a live break-the-glass session unlocks the foreign sensitive
        // problems the D3 rule withholds; the ledger row names the session.
        boolean unlocked = readable != null && breakGlassGate.isUnlocked(requesterUserId, patientId, hospitalId);
        List<AllergySummaryDTO> allergies = loadAllergies(patientId);
        // E9 #64 — what D3 withholds is counted, so the banner can say so.
        WithheldRows withheld = new WithheldRows();
        List<ProblemSummaryDTO> problems = loadProblems(patientId, hospitalId, readable, unlocked, withheld);
        ActiveEncounterDTO activeEncounter = loadActiveEncounter(patientId, hospitalId);
        CodeStatusDTO codeStatus = loadCodeStatus(patient, readable);
        // E9 #60 — the storyboard surfaces foreign allergies (#56), problems and
        // directives (#59a); every one of them is accounted, once per source
        // hospital for the whole banner. #56 and #59a left this ledger row out
        // because the call carries no requester; the context has one.
        if (readable != null) {
            Map<String, Long> reach = CrossHospitalReachRecorder.reachOf(
                allergies.stream().map(AllergySummaryDTO::getHospitalId).toList(), hospitalId);
            CrossHospitalReachRecorder.merge(reach, CrossHospitalReachRecorder.reachOf(
                problems.stream().map(ProblemSummaryDTO::getHospitalId).toList(), hospitalId));
            if (codeStatus != null && codeStatus.getDirectives() != null) {
                CrossHospitalReachRecorder.merge(reach, CrossHospitalReachRecorder.reachOf(
                    codeStatus.getDirectives().stream().map(DirectiveSummaryDTO::getHospitalId).toList(), hospitalId));
            }
            reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null, reach,
                "Cross-hospital storyboard read on the treatment relationship");
        }

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
            .restrictedRows(withheld.summaries())
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

    /**
     * E9 #56 — allergies follow the patient: every active row, whichever
     * hospital recorded it, with that hospital named on the chip. A
     * penicillin allergy recorded at Hôpital A is exactly the row the ED at
     * Hôpital B must see.
     */
    private List<AllergySummaryDTO> loadAllergies(UUID patientId) {
        List<PatientAllergy> source = allergyRepository.findByPatient_Id(patientId);
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

    /**
     * E9 #59 — problems follow the patient across the readable hospitals,
     * with the recording hospital on the chip. A foreign problem carrying a
     * sensitivity category is withheld (D3) and counted (#64); it opens via
     * break-the-glass.
     */
    private List<ProblemSummaryDTO> loadProblems(UUID patientId, UUID hospitalId, Set<UUID> readable,
                                                 boolean unlocked, WithheldRows withheld) {
        List<PatientProblem> source = readable != null
            ? problemRepository.findByPatient_IdAndHospital_IdIn(patientId, readable)
            : problemRepository.findByPatient_Id(patientId);
        return source.stream()
            .filter(p -> withheld.admit(p.getHospital(), null, hospitalId,
                sensitivityClassifier.effectiveCategory(p), unlocked))
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

    /** E9 #59 — a code status is a property of the patient; directives travel with them. */
    private CodeStatusDTO loadCodeStatus(Patient patient, Set<UUID> readable) {
        List<AdvanceDirective> directives = readable != null
            ? advanceDirectiveRepository.findByPatient_IdAndHospital_IdIn(patient.getId(), readable)
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
            .hospitalId(a.getHospital() != null ? a.getHospital().getId() : null)
            .hospitalName(a.getHospital() != null ? a.getHospital().getName() : null)
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
            .hospitalId(p.getHospital() != null ? p.getHospital().getId() : null)
            .hospitalName(p.getHospital() != null ? p.getHospital().getName() : null)
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
            .hospitalId(d.getHospital() != null ? d.getHospital().getId() : null)
            .hospitalName(d.getHospital() != null ? d.getHospital().getName() : null)
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
